/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.jetty.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

public class EmbeddedJettyHttp2Test {

	public static final Logger LOG = LoggerFactory.getLogger(EmbeddedJettyHttp2Test.class);

	// https://httpwg.org/specs/rfc7540.html
	// https://sookocheff.com/post/networking/how-does-http-2-work/

	@Test
	public void http11NioExchange() throws Exception {
		QueuedThreadPool qtp = new QueuedThreadPool(10);
		qtp.setName("jetty-qtp");

		Server server = new Server(qtp);

		ServerConnector connector = new ServerConnector(server, 1, 1);
		connector.setPort(0);
		LOG.info("Local port before start: {}", connector.getLocalPort());
		connector.clearConnectionFactories();
		HttpConfiguration config = new HttpConfiguration();

		connector.addConnectionFactory(new HttpConnectionFactory(config));

		server.setConnectors(new Connector[] { connector });

		server.setHandler(new AbstractHandler() {
			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
				LOG.info("Handling request {} from {}:{}", target, request.getRemoteAddr(), request.getRemotePort());
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("OK\n");
				response.getWriter().close();
			}
		});

		server.start();

		LOG.info("Local port after start: {}", connector.getLocalPort());

		AtomicInteger count = new AtomicInteger(1);
		ExecutorService pool = Executors.newFixedThreadPool(4, r -> new Thread(r, "pool-thread-" + count.getAndIncrement()));

		Selector selector = Selector.open();

		SocketChannel sc = SocketChannel.open();
		// Before a channel is registered with a selector, it _must_ be in non-blocking mode
		sc.configureBlocking(false);

		// https://stackoverflow.com/questions/6540346/java-solaris-nio-op-connect-problem
		// https://stackoverflow.com/questions/19740436/does-order-of-operation-and-registration-matter-on-selectable-channel
		//  - registration for OP_CONNECTION should be done ONLY after sc.connect() returns false
		//  - then after key.isConnectable() returns true, sc.finishConnect() has to be called
		//    but it still may return false
		//  - only after sc.finishConnect() returns true, OP_CONNECT may be removed from interested ops

		SelectionKey key = null;
		int selected;

		boolean connected = sc.connect(new InetSocketAddress("127.0.0.1", connector.getLocalPort()));
		LOG.info("Connected: {}", connected);
		if (!connected) {
			key = sc.register(selector, SelectionKey.OP_CONNECT);
			while (true) {
				boolean done = false;
				selected = selector.select(100L);
				if (selected > 0) {
					Set<SelectionKey> keys = selector.selectedKeys();
					for (SelectionKey k : keys) {
						if (k.isConnectable()) {
							boolean finished = ((SocketChannel) k.channel()).finishConnect();
							LOG.info("Finished connecting: {}", finished);
							if (finished) {
								k.interestOps(k.interestOps() & ~SelectionKey.OP_CONNECT);
								done = true;
							}
						}
					}
				}
				if (done) {
					break;
				}
			}
		}

		// we're connected, we can prepare for writing
		ByteBuffer buffer = ByteBuffer.wrap(new byte[4096]);
		if (key == null) {
			key = sc.register(selector, SelectionKey.OP_WRITE);
		} else {
			key.interestOps(SelectionKey.OP_WRITE);
		}

		buffer.put("GET /test?x=y&a=b HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
		buffer.put("Host: 127.0.0.1\r\n".getBytes(StandardCharsets.UTF_8));
		buffer.put("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));

		while (true) {
			boolean done = false;
			selected = selector.select(100L);
			if (selected > 0) {
				Set<SelectionKey> keys = selector.selectedKeys();
				for (SelectionKey k : keys) {
					if (k.isWritable()) {
						buffer.flip();
						int written = ((SocketChannel) k.channel()).write(buffer);
						LOG.info("Written {} bytes", written);
						done = true;
						k.interestOps(k.interestOps() & ~SelectionKey.OP_WRITE);
					}
				}
			}
			if (done) {
				break;
			}
		}

		buffer.clear();
		key.interestOps(SelectionKey.OP_READ);

		while (true) {
			boolean done = false;
			selected = selector.select(100L);
			if (selected > 0) {
				Set<SelectionKey> keys = selector.selectedKeys();
				for (SelectionKey k : keys) {
					if (k.isReadable()) {
						int read = ((SocketChannel) k.channel()).read(buffer);
						LOG.info("Read {} bytes", read);
						done = read > 0;
					}
				}
			}
			if (done) {
				break;
			}
		}

		buffer.flip();
		LOG.info("Response\n{}", new String(buffer.array(), 0, buffer.limit()));

		// end of processing
		key.cancel();

		selector.close();

		server.stop();
		server.join();
	}

	@Test
	public void http2NioExchange() throws Exception {
		QueuedThreadPool qtp = new QueuedThreadPool(10);
		qtp.setName("jetty-qtp");

		Server server = new Server(qtp);

		ServerConnector connector = new ServerConnector(server, 1, 1);
		connector.setPort(8123);
		LOG.info("Local port before start: {}", connector.getLocalPort());
		connector.clearConnectionFactories();
		HttpConfiguration config = new HttpConfiguration();

		connector.addConnectionFactory(new HttpConnectionFactory(config));
		connector.addConnectionFactory(new HTTP2CServerConnectionFactory(config));
		connector.setIdleTimeout(3600000);

		server.setConnectors(new Connector[] { connector });

		server.setHandler(new AbstractHandler() {
			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
				LOG.info("Handling request {} from {}:{}", target, request.getRemoteAddr(), request.getRemotePort());
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("OK\n");
				response.getWriter().close();
			}
		});

		server.start();

		LOG.info("Local port after start: {}", connector.getLocalPort());

		AtomicInteger count = new AtomicInteger(1);
		ExecutorService pool = Executors.newFixedThreadPool(4, r -> new Thread(r, "pool-thread-" + count.getAndIncrement()));

		Selector selector = Selector.open();

		SocketChannel sc = SocketChannel.open();
		sc.configureBlocking(false);
		SelectionKey key = null;
		int selected;

		boolean connected = sc.connect(new InetSocketAddress("127.0.0.1", connector.getLocalPort()));
		LOG.info("Connected: {}", connected);
		if (!connected) {
			key = sc.register(selector, SelectionKey.OP_CONNECT);
			while (true) {
				boolean done = false;
				selected = selector.select(100L);
				if (selected > 0) {
					Set<SelectionKey> keys = selector.selectedKeys();
					for (SelectionKey k : keys) {
						if (k.isConnectable()) {
							boolean finished = ((SocketChannel) k.channel()).finishConnect();
							LOG.info("Finished connecting: {}", finished);
							if (finished) {
								k.interestOps(k.interestOps() & ~SelectionKey.OP_CONNECT);
								done = true;
							}
						}
					}
				}
				if (done) {
					break;
				}
			}
		}

		// we're connected, we can prepare for writing
		ByteBuffer buffer = ByteBuffer.wrap(new byte[4096]);
		if (key == null) {
			key = sc.register(selector, SelectionKey.OP_WRITE);
		} else {
			key.interestOps(SelectionKey.OP_WRITE);
		}

		buffer.put("GET /test?x=y&a=b HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
		buffer.put("Host: 127.0.0.1\r\n".getBytes(StandardCharsets.UTF_8));
		// https://httpwg.org/specs/rfc7540.html#rfc.section.3.2.1
		buffer.put("Connection: Upgrade, HTTP2-Settings\r\n".getBytes(StandardCharsets.UTF_8));
		buffer.put("Upgrade: h2c\r\n".getBytes(StandardCharsets.UTF_8));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream settings = new DataOutputStream(baos);
		// SETTINGS_HEADER_TABLE_SIZE (0x1)
		settings.writeShort(0x01);
		settings.writeInt(0x1000); // 4096 by default
		// SETTINGS_ENABLE_PUSH (0x2)
		settings.writeShort(0x02);
		settings.writeInt(0x00); // 1 by default, 0 disables PUSH_PROMISE frame
		// SETTINGS_MAX_CONCURRENT_STREAMS (0x3)
		settings.writeShort(0x03);
		settings.writeInt(0xFF); // no default value. recommended >100
		// SETTINGS_INITIAL_WINDOW_SIZE (0x4)
		settings.writeShort(0x04);
		settings.writeInt(0xFFFE); // defaults to 65535
		// SETTINGS_MAX_FRAME_SIZE (0x5)
		settings.writeShort(0x05);
		settings.writeInt(0x4000); // defaults to 16384 (2^14)
		// SETTINGS_MAX_HEADER_LIST_SIZE (0x6)
		settings.writeShort(0x06);
		settings.writeInt(0x8000); // no default value.
		settings.close();
		buffer.put(("HTTP2-Settings: " + new String(Base64.getUrlEncoder().encode(baos.toByteArray())) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

		// Starting HTTP/2 for "http" URIs - https://httpwg.org/specs/rfc7540.html#discover-http
		LOG.info("Request\n{}", new String(buffer.array(), 0, buffer.position()));
		send(selector, key, buffer);

		// now we should get "HTTP/1.1 101 Switching Protocols", but we may get some HTTP/2 frames immediately, but in more reads.
		receive(selector, key, buffer);

		String fullResponse = new String(buffer.array(), 0, buffer.limit());
		String response = fullResponse;
		int rnrn = response.indexOf("\r\n\r\n");
		if (rnrn > 0) {
			response = response.substring(0, rnrn + 4);
		}
		LOG.info("Response\n{}", response);
		assertThat(fullResponse, startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
		buffer.position(rnrn + 4);
		boolean remains = true;
		while (remains) {
			remains = decodeFrame(buffer, true);
		}

		// even at this stage, we should have everything including response HEADERS and DATA frames
		// this doesn't mean we shouldn't ACK server's SETTINGS

		// end of processing
		key.cancel();

		selector.close();

		server.stop();
		server.join();
	}

	@Test
	public void http2NioExchangeWithDirectUpgrade() throws Exception {
		QueuedThreadPool qtp = new QueuedThreadPool(10);
		qtp.setName("jetty-qtp");

		Server server = new Server(qtp);

		ServerConnector connector = new ServerConnector(server, 1, 1);
		connector.setPort(8123);
		LOG.info("Local port before start: {}", connector.getLocalPort());
		connector.clearConnectionFactories();
		HttpConfiguration config = new HttpConfiguration();

		connector.addConnectionFactory(new HttpConnectionFactory(config));
		connector.addConnectionFactory(new HTTP2CServerConnectionFactory(config));
		connector.setIdleTimeout(3600000);

		server.setConnectors(new Connector[] { connector });

		server.setHandler(new AbstractHandler() {
			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
				LOG.info("Handling request {} from {}:{}", target, request.getRemoteAddr(), request.getRemotePort());
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("OK\n");
				response.getWriter().close();
			}
		});

		server.start();

		LOG.info("Local port after start: {}", connector.getLocalPort());

		AtomicInteger count = new AtomicInteger(1);
		ExecutorService pool = Executors.newFixedThreadPool(4, r -> new Thread(r, "pool-thread-" + count.getAndIncrement()));

		Selector selector = Selector.open();

		SocketChannel sc = SocketChannel.open();
		sc.configureBlocking(false);
		SelectionKey key = null;
		int selected;

		boolean connected = sc.connect(new InetSocketAddress("127.0.0.1", connector.getLocalPort()));
		LOG.info("Connected: {}", connected);
		if (!connected) {
			key = sc.register(selector, SelectionKey.OP_CONNECT);
			while (true) {
				boolean done = false;
				selected = selector.select(100L);
				if (selected > 0) {
					Set<SelectionKey> keys = selector.selectedKeys();
					for (SelectionKey k : keys) {
						if (k.isConnectable()) {
							boolean finished = ((SocketChannel) k.channel()).finishConnect();
							LOG.info("Finished connecting: {}", finished);
							if (finished) {
								k.interestOps(k.interestOps() & ~SelectionKey.OP_CONNECT);
								done = true;
							}
						}
					}
				}
				if (done) {
					break;
				}
			}
		}

		// we're connected, we can prepare for writing
		ByteBuffer buffer = ByteBuffer.wrap(new byte[4096]);
		if (key == null) {
			key = sc.register(selector, SelectionKey.OP_WRITE);
		} else {
			key.interestOps(SelectionKey.OP_WRITE);
		}

		// https://httpwg.org/specs/rfc7540.html#ConnectionHeader
		// [...] the connection preface starts with the string PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n).

		buffer.clear();
		buffer.put("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.UTF_8));
		// This sequence MUST be followed by a SETTINGS frame (Section 6.5), which MAY be empty
		buffer.put(new byte[] {
				0x00, 0x00, 0x24,      // length (6*6)
				0x04,                  // type = SETTINGS
				0x00,                  // flags
				0x00, 0x00, 0x00, 0x00 // stream identifier
		});

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream settings = new DataOutputStream(baos);
		// SETTINGS_HEADER_TABLE_SIZE (0x1)
		settings.writeShort(0x01);
		settings.writeInt(0x1000); // 4096 by default
		// SETTINGS_ENABLE_PUSH (0x2)
		settings.writeShort(0x02);
		settings.writeInt(0x00); // 1 by default, 0 disables PUSH_PROMISE frame
		// SETTINGS_MAX_CONCURRENT_STREAMS (0x3)
		settings.writeShort(0x03);
		settings.writeInt(0xFF); // no default value. recommended >100
		// SETTINGS_INITIAL_WINDOW_SIZE (0x4)
		settings.writeShort(0x04);
		settings.writeInt(0xFFFE); // defaults to 65535
		// SETTINGS_MAX_FRAME_SIZE (0x5)
		settings.writeShort(0x05);
		settings.writeInt(0x4000); // defaults to 16384 (2^14)
		// SETTINGS_MAX_HEADER_LIST_SIZE (0x6)
		settings.writeShort(0x06);
		settings.writeInt(0x8000); // no default value.
		settings.close();
		buffer.put(baos.toByteArray(), 0, 36);

		// Starting HTTP/2 for "http" URIs - https://httpwg.org/specs/rfc7540.html#discover-http
		send(selector, key, buffer);

		// we should NOT get any HTTP/1.1 response - just HTTP/2 frames - most probably SETTINGS and WINDOW_UPDATE
		receive(selector, key, buffer);
		boolean remains = true;
		while (remains) {
			remains = decodeFrame(buffer, true);
		}

		// ACK server SETTINGS
		buffer.clear();
		// This sequence MUST be followed by a SETTINGS frame (Section 6.5), which MAY be empty
		buffer.put(new byte[] {
				0x00, 0x00, 0x00,      // length
				0x04,                  // type = SETTINGS
				0x01,                  // flags = ACK
				0x00, 0x00, 0x00, 0x00 // stream identifier
		});
		send(selector, key, buffer);

		// With "PRI * HTTP/2.0" there's no actual request sent, so we have to prepare one
		buffer.clear();
		int pos = buffer.position();
		buffer.put(new byte[] {
				0x00, 0x00, 0x00,      // length - placeholder
				0x01,                  // type = HEADERS
				0x04 | 0x01,           // flags - END_HEADERS | END_STREAM
				0x00, 0x00, 0x00, 0x01 // stream identifier - arbitrary, should be taken from a sequence
		});
		HpackEncoder encoder = new HpackEncoder();
		HttpFields fields = new HttpFields();
		fields.add(new HostPortHttpField("127.0.0.1", 0));
		MetaData md = new MetaData.Request("GET", "http", new HostPortHttpField("127.0.0.1", 0), "/test",
				HttpVersion.HTTP_2, fields, 0L);
//		MetaData md = new MetaData.Request("GET", HttpURI.createHttpURI("http", "127.0.0.1", connector.getLocalPort(), "/test", null, null, null),
//				HttpVersion.HTTP_2, fields);
		encoder.encode(buffer, md);
		int length = buffer.position() - 9;
		buffer.array()[pos + 2] = (byte) (length & 0xFF);
		buffer.array()[pos + 1] = (byte) (length >> 8 & 0xFF);
		send(selector, key, buffer);

		receive(selector, key, buffer);
		remains = true;
		while (remains) {
			remains = decodeFrame(buffer, true);
		}

		// end of processing
		key.cancel();

		selector.close();

		server.stop();
		server.join();
	}

	private void send(Selector selector, SelectionKey key, ByteBuffer buffer) throws IOException {
		int selected;
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
		while (true) {
			boolean done = false;
			selected = selector.select(100L);
			if (selected > 0) {
				Set<SelectionKey> keys = selector.selectedKeys();
				for (SelectionKey k : keys) {
					if (k.isWritable()) {
						buffer.flip();
						int written = ((SocketChannel) k.channel()).write(buffer);
						LOG.info("Written {} bytes", written);
						done = true;
						k.interestOps(k.interestOps() & ~SelectionKey.OP_WRITE);
					}
				}
				selector.selectedKeys().clear();
			}
			if (done) {
				break;
			}
		}
		buffer.clear();
	}

	private void receive(Selector selector, SelectionKey key, ByteBuffer buffer) throws IOException {
		int selected;
		key.interestOps(SelectionKey.OP_READ);

		while (true) {
			selected = selector.select(200L);
			if (selected > 0) {
				Set<SelectionKey> keys = selector.selectedKeys();
				for (SelectionKey k : keys) {
					if (k.isReadable()) {
						while (true) {
							int read = ((SocketChannel) k.channel()).read(buffer);
							if (read == 0) {
								break;
							}
							LOG.info("Read {} bytes", read);
						}

					}
				}
				// this is needed if we want to read more!
				selector.selectedKeys().clear();
				continue;
			}
			break;
		}

		buffer.flip();
	}

	// https://httpwg.org/specs/rfc7540.html#FramingLayer
	private boolean decodeFrame(ByteBuffer buffer, boolean incoming) throws IOException, HpackException.SessionException, HpackException.StreamException {
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buffer.array(), buffer.position(), buffer.limit()));
		int length = 0;
		length |= dis.readByte() << 16;
		length |= dis.readByte() << 8;
		length |= dis.readByte();
		byte type = dis.readByte();
		byte flags = dis.readByte();
		int sid = dis.readInt();
		byte[] payload = new byte[length];
		int read = dis.read(payload, 0, length);
		if (read >= 0) {
			assertThat(read, equalTo(length));
		}
		buffer.position(buffer.position() + 3 + 1 + 1 + 4 + length);

		DataInputStream fdis = new DataInputStream(new ByteArrayInputStream(payload, 0, length));

		switch (type) {
			case 0x00: { // DATA https://httpwg.org/specs/rfc7540.html#DATA
				if (incoming) {
					LOG.info("< Received DATA frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending DATA frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				boolean padded = (flags & 0x08) != 0;
				int offset = 0;
				if (padded) {
					offset++;
				}
				LOG.info(" - DATA: {}", new String(payload, offset, length - offset));
				break;
			}
			case 0x01: { // HEADERS https://httpwg.org/specs/rfc7540.html#HEADERS
				if (incoming) {
					LOG.info("< Received HEADERS frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending HEADERS frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				boolean padded = (flags & 0x08) != 0;
				boolean last = (flags & 0x04) != 0;
				boolean priority = (flags & 0x20) != 0;
				byte padLength = padded ? fdis.readByte() : 0;
				int streamDependency = priority ? fdis.readInt() : 0;
				int weight = priority ? (int) fdis.readByte() : 0;
				// https://httpwg.org/specs/rfc7540.html#HeaderBlock
				// see org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory.newConnection
				HpackDecoder decoder = new HpackDecoder(4096, 8192);
				int offset = 0;
				if (padded) {
					offset++;
				}
				if (priority) {
					offset += 5;
				}
				MetaData md = decoder.decode(ByteBuffer.wrap(payload, offset, length - offset));
				LOG.info(" - HEADERS::HTTP version: {}", md.getHttpVersion());
				for (HttpField f : md.getFields()) {
					LOG.info(" - HEADERS::{}: {}", f.getName(), f.getValue());
				}
				break;
			}
			case 0x02: { // PRIORITY https://httpwg.org/specs/rfc7540.html#PRIORITY
				if (incoming) {
					LOG.info("< Received PRIORITY frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending PRIORITY frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				break;
			}
			case 0x03: { // RST_STREAM https://httpwg.org/specs/rfc7540.html#RST_STREAM
				if (incoming) {
					LOG.info("< Received RST_STREAM frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending RST_STREAM frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				break;
			}
			case 0x04: { // SETTINGS https://httpwg.org/specs/rfc7540.html#SETTINGS
				if (incoming) {
					LOG.info("< Received SETTINGS frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending SETTINGS frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				int c = 0;
				while (c < length) {
					short id = fdis.readShort();
					int v = fdis.readInt();
					c += 6;
					switch (id) {
						case 0x01: { // SETTINGS_HEADER_TABLE_SIZE
							LOG.info(" - SETTINGS::SETTINGS_HEADER_TABLE_SIZE: {}", v);
							break;
						}
						case 0x02: { // SETTINGS_ENABLE_PUSH
							LOG.info(" - SETTINGS::SETTINGS_ENABLE_PUSH: {}", v);
							break;
						}
						case 0x03: { // SETTINGS_MAX_CONCURRENT_STREAMS
							LOG.info(" - SETTINGS::SETTINGS_MAX_CONCURRENT_STREAMS: {}", v);
							break;
						}
						case 0x04: { // SETTINGS_INITIAL_WINDOW_SIZE
							LOG.info(" - SETTINGS::SETTINGS_INITIAL_WINDOW_SIZE: {}", v);
							break;
						}
						case 0x05: { // SETTINGS_MAX_FRAME_SIZE
							LOG.info(" - SETTINGS::SETTINGS_MAX_FRAME_SIZE: {}", v);
							break;
						}
						case 0x06: { // SETTINGS_MAX_HEADER_LIST_SIZE
							LOG.info(" - SETTINGS::SETTINGS_MAX_HEADER_LIST_SIZE: {}", v);
							break;
						}
						default:
							break;
					}
				}
				break;
			}
			case 0x05: { // PUSH_PROMISE https://httpwg.org/specs/rfc7540.html#PUSH_PROMISE
				if (incoming) {
					LOG.info("< Received PUSH_PROMISE frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending PUSH_PROMISE frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				break;
			}
			case 0x06: { // PING https://httpwg.org/specs/rfc7540.html#PING
				if (incoming) {
					LOG.info("< Received PING frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending PING frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				break;
			}
			case 0x07: { // GOAWAY https://httpwg.org/specs/rfc7540.html#GOAWAY
				if (incoming) {
					LOG.info("< Received GOAWAY frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending GOAWAY frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				break;
			}
			case 0x08: { // WINDOW_UPDATE https://httpwg.org/specs/rfc7540.html#WINDOW_UPDATE
				if (incoming) {
					LOG.info("< Received WINDOW_UPDATE frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending WINDOW_UPDATE frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				int v = fdis.readInt();
				LOG.info(" - WINDOW_UPDATE::Window Size Increment: {}", v & ~0x80000000);
				break;
			}
			case 0x09: { // CONTINUATION https://httpwg.org/specs/rfc7540.html#CONTINUATION
				if (incoming) {
					LOG.info("< Received CONTINUATION frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				} else {
					LOG.info("> Sending CONTINUATION frame (flags: {}, sid: {}, length: {})", flags, sid, length);
				}
				break;
			}
			default:
				break;
		}

		return buffer.hasRemaining();
	}

	@Test
	@Ignore("Just local tool")
	public void decodeHPACK() throws Exception {
		FileInputStream fis1 = new FileInputStream("/data/tmp/hpack5.dat");
		byte[] b1 = new byte[48];
		fis1.read(b1, 0, 26);
		fis1.close();

		HpackDecoder decoder = new HpackDecoder(4096, 8192);
		MetaData m1 = decoder.decode(ByteBuffer.wrap(b1, 0, 26));
		System.out.println();
	}

}
