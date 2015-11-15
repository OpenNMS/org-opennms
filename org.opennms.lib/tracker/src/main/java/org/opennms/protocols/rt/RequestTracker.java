/*
 * This file is part of the OpenNMS(R) Application.
 *
 * OpenNMS(R) is Copyright (C) 2007 The OpenNMS Group, Inc.  All rights reserved.
 * OpenNMS(R) is a derivative work, containing both original code, included code and modified
 * code that was published under the GNU General Public License. Copyrights for modified
 * and included code are below.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * Modifications:
 * 
 * Created January 31, 2007
 *
 * Copyright (C) 2007 The OpenNMS Group, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * For more information contact:
 *      OpenNMS Licensing       <license@opennms.org>
 *      http://www.opennms.org/
 *      http://www.opennms.com/
 */
package org.opennms.protocols.rt;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * Request Tracker Design
 * 
 * The request tracker has four components that are all static
 * 
 * a messenger
 * a pending requests map
 * a callback queue (LinkedBlockingQueue)
 * a timeout queue (DelayQueue)
 * 
 * It also has two threads:
 * 
 * a thread to process the callbacks (Callback-Processor)
 * a thread to process the timeouts (Timeout-Processor)
 *
 * Thread Details:
 *
 * 1.  The callback processor thread is responsible for handling all of the callbacks to
 *     the request object. This thread will pull callbacks off the linked blocking queue
 *     and issue the call in the order in which they were added.
 * 
 *     All of the callback are handled from a single thread in order to avoid synchronization
 *     issue in processing the replies and responses. In the versions of the tracker before 0.7, 
 *     it was possible for RequestTracker to receive a reply, but issue the timeout before it had
 *     a chance to process it.
 *
 * 2.  The timeout processor is only responsible creating callbacks for timeouts, and adding these
 *     to the callback queue when timeouts occur. The timeout processor pulls the requests off a DelayQueue
 *     and creates a callback if the request had not yet been processed. Note that a DelayQueue does not allow
 *     things to be removed until the timeout has expired.
 *
 * Processing:
 * 
 * All requests are asynchronous (if synchronous requests are need that
 * are implemented using asynchronous requests and blocking callbacks)
 * 
 * Making a request: (client thread)
 * - create a request (client does this)
 * - send the request (via the Messenger)
 * - add it to the timeout queue
 *
 * Replies come from the messenger:
 * - as replies come in, the messenger invokes the handleReply method on the request tracker
 *   (which was passed during initializing)
 * - the handleReply method adds a callback that will process the reply to the callback queue
 * - when called, this callback will:
 * -- look up and remove the matching request in the pendingRequest map
 * -- call request.processReply(reply) - this will store the reply and
 * -- call the handleReply call back
 * -- pending request sets completed to true
 *
 * Processing a timeout: (Timeout-Processor)
 * - take a request from the timeout queue
 * - if the request is completed discard it
 * - add a callback to the callback queue process the timedout request:
 * - when called, this callback will:
 * -- discard the request if it was completed
 * -- call request.processTimeout(), this will check the number
 *    of retries and either return a new request with fewer retries or
 *    call the handleTimeout call back
 * -- if processTimeout returns a new request than process it as in Making a request
 *
 * Processing a callback: (Callback-Processor)
 * - take a callback from the callbackQueue queue
 * - issue the callback
 */

/**
 * A class for tracking sending and received of arbitrary messages. The
 * transport mechanism is irrelevant and is encapsulated in the Messenger
 * request. Timeouts and Retries are handled by this mechanism and provided to
 * the request object so they can be processed. A request is guaranteed to
 * have one of its process method called no matter what happens. This makes it
 * easier to write code because some kind of indication is always provided and
 * so timing out is not needed in the client.
 *
 * @author jwhite
 * @author <a href="mailto:brozow@opennms.org">Mathew Brozowski</a>
 */
public class RequestTracker<ReqT extends Request<?, ReqT, ReplyT>, ReplyT extends Response> implements ReplyHandler<ReplyT> {
    
    private static final Logger s_log = LoggerFactory.getLogger(RequestTracker.class);
    
    private RequestLocator<ReqT, ReplyT> m_requestLocator;
    private Messenger<ReqT, ReplyT> m_messenger;
    private final BlockingQueue<Callable<Void>> m_callbackQueue;
    private DelayQueue<ReqT> m_timeoutQueue;

    private Thread m_callbackProcessor;
    private Thread m_timeoutProcessor;
    
    private static final int NEW = 0;
    private static final int STARTING = 1;
    private static final int STARTED = 2;
    
    private AtomicInteger m_state = new AtomicInteger(NEW);

	/**
     * Construct a RequestTracker that sends and received messages using the
     * indicated messenger. The name is using to name the threads created by
     * the tracker.
     */
    public RequestTracker(String name, Messenger<ReqT, ReplyT> messenger, RequestLocator<ReqT, ReplyT> requestLocator) throws IOException {
        
        m_requestLocator = requestLocator;
        m_callbackQueue = new LinkedBlockingQueue<Callable<Void>>();
	    m_timeoutQueue = new DelayQueue<ReqT>();

	    m_callbackProcessor = new Thread(name+"-Callback-Processor") {
	        public void run() {
	            try {
	                processCallbacks();
	            } catch (InterruptedException e) {
                    s_log.error("Thread {} interrupted!", this);
	            } catch (Throwable t) {
                    s_log.error("Unexpected exception on Thread " + this + "!", t);
	            }
	        }
	    };

	    m_timeoutProcessor = new Thread(name+"-Timeout-Processor") {
	        public void run() {
	            try {
	                processTimeouts();
	            } catch (InterruptedException e) {
                    s_log.error("Thread {} interrupted!", this);
	            } catch (Throwable t) {
                    s_log.error("Unexpected exception on Thread " + this + "!", t);
	            }
	        }
	    };
	    
        m_messenger = messenger;
	}
    
    /**
     * This method starts all the threads that are used to process the
     * messages and also starts the messenger.
     */
    public synchronized void start() {
        boolean startNeeded = m_state.compareAndSet(NEW, STARTING);
        if (startNeeded) {
            m_messenger.start(this);
            m_timeoutProcessor.start();
            m_callbackProcessor.start();
            m_state.set(STARTED);
        }
    }

    public void assertStarted() {
        boolean started = m_state.get() == STARTED;
        if (!started) throw new IllegalStateException("RequestTracker not started!");
    }

    /**
     * Send a tracked request via the messenger. The request is tracked for
     * timeouts and retries. Retries are sent if the timeout processing
     * indicates that they should be.
     */
    public void sendRequest(ReqT request) throws Exception {
        assertStarted();
        if (!m_requestLocator.trackRequest(request)) return;
        m_messenger.sendRequest(request);
        s_log.debug("Scheding timeout for request to {} in {} ms", request, request.getDelay(TimeUnit.MILLISECONDS));
        m_timeoutQueue.offer(request);
    }

    public void handleReply(final ReplyT reply) {
        m_callbackQueue.add(new Callable<Void>() {
            public Void call() throws Exception {
                onProcessReply(reply);
                return null;
            }
        });
    }

    private ReqT locateMatchingRequest(ReplyT reply) {
        try {
            return m_requestLocator.locateMatchingRequest(reply);
        } catch (Throwable t) {
            s_log.error("Unexpected error locating response to request " + reply + ". Discarding response!", t);
            return null;
        }
    }

    private void processCallbacks() throws InterruptedException {
        while (true) {
            Callable<Void> callback = m_callbackQueue.take();
            try {
                callback.call();
            } catch (Exception e) {
                s_log.error("Failed to issue callback {}.", callback, e);
            }
        }
    }

	private void processTimeouts() throws InterruptedException {  
	    while (true) {
            final ReqT timedOutRequest = m_timeoutQueue.take();

            // do nothing is the request has already been processed
            if (timedOutRequest.isProcessed()) {
                continue;
            }

            // the request hasn't been processed yet, but we'll
            // check again when the callback is issued
            m_callbackQueue.add(new Callable<Void>() {
                public Void call() throws Exception {
                    onProcessTimeout(timedOutRequest);
                    return null;
                }
            });
	    }
	}

	private void onProcessReply(ReplyT reply) {
	    s_log.debug("Processing reply: {}", reply);

        ReqT request = locateMatchingRequest(reply);

        if (request != null) {
            boolean isComplete;

            try {
                s_log.debug("Processing reply {} for request {}", reply, request);
                isComplete = request.processResponse(reply);
            } catch (Throwable t) {
                s_log.error("Unexpected error processingResponse to request: {}, reply is {}", request, reply, t);
                // we should throw away the request if this happens
                isComplete = true;
            }

            if (isComplete) {
                m_requestLocator.requestComplete(request);
            }
        } else {
            s_log.info("No request found for reply {}", reply);
        }
	}

    private void onProcessTimeout(ReqT timedOutRequest) {
        // do nothing is the request has already been processed.
        if (timedOutRequest.isProcessed()) {
            return;
        }

        s_log.debug("Processing a possibly timed-out request: {}", timedOutRequest);
        ReqT pendingRequest = m_requestLocator.requestTimedOut(timedOutRequest);

        if (pendingRequest == timedOutRequest) {
            // the request is still pending, we must time it out
            ReqT retry = null;
            try {
                s_log.debug("Processing timeout for: {}", timedOutRequest);
                retry = timedOutRequest.processTimeout();
            } catch (Throwable t) {
                s_log.error("Unexpected error processingTimout to request: {}", timedOutRequest, t);
                retry = null;
            }

            if (retry != null) {
                try {
                    sendRequest(retry);
                } catch (Exception e) {
                    retry.processError(e);
                }
            }
        } else if (pendingRequest != null) {
            String msg = String.format("A pending request %s with the same id exists but is not the timeout request %s from the queue!", pendingRequest, timedOutRequest);
            s_log.error(msg);
            timedOutRequest.processError(new IllegalStateException(msg));
        }
    }
}
