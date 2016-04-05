package com.gentics.mesh.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.gentics.mesh.core.data.root.RootVertex;
import com.gentics.mesh.core.node.ElementEntry;
import com.gentics.mesh.graphdb.spi.Database;
import com.gentics.mesh.test.TestUtils;

import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public final class MeshAssert {

	private static final Logger log = LoggerFactory.getLogger(MeshAssert.class);

	private static final Integer CI_TIMEOUT_SECONDS = 60;

	private static final Integer DEV_TIMEOUT_SECONDS = 10000;

	public static void assertSuccess(Future<?> future) {
		if (future.cause() != null) {
			future.cause().printStackTrace();
		}
		assertTrue("The future failed with error {" + (future.cause() == null ? "Unknown error" : future.cause().getMessage()) + "}",
				future.succeeded());
	}

	public static void assertElement(RootVertex<?> root, String uuid, boolean exists) throws Exception {
		root.reload();
		Object element = root.findByUuid(uuid).toBlocking().first();
		if (exists) {
			assertNotNull("The element should exist.", element);
		} else {
			assertNull("The element should not exist.", element);
		}

	}

	public static int getTimeout() throws UnknownHostException {
		int timeout = CI_TIMEOUT_SECONDS;
		if (TestUtils.isHost("plexus") || TestUtils.isHost("satan3.office")) {
			timeout = DEV_TIMEOUT_SECONDS;
		}
		if (log.isDebugEnabled()) {
			log.debug("Using test timeout of {" + timeout + "} seconds for host {" + TestUtils.getHostname() + "}");
		}
		return timeout;
	}

	public static void latchFor(Future<?> future) {
		CountDownLatch latch = new CountDownLatch(1);
		future.setHandler(rh -> {
			latch.countDown();
		});
		try {
			assertTrue("The timeout of the latch was reached.", latch.await(getTimeout(), TimeUnit.SECONDS));
		} catch (UnknownHostException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void failingLatch(CountDownLatch latch, int timeoutInSeconds) throws InterruptedException {
		if (!latch.await(timeoutInSeconds, TimeUnit.SECONDS)) {
			fail("Latch timeout reached");
		}
	}

	public static void failingLatch(CountDownLatch latch) throws Exception {
		if (!latch.await(getTimeout(), TimeUnit.SECONDS)) {
			fail("Latch timeout reached");
		}
	}

	public static void assertDeleted(Map<String, ElementEntry> uuidToBeDeleted) {
		for (String key : uuidToBeDeleted.keySet()) {
			ElementEntry entry = uuidToBeDeleted.get(key);
			assertFalse("The element {" + key + "} vertex for uuid: {" + entry.getUuid() + "}",
					Database.getThreadLocalGraph().v().has("uuid", entry.getUuid()).hasNext());
		}
	}

}
