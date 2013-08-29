/*
 * PriorityQueueStrict.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2013 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */



package tigase.util;

//~--- JDK imports ------------------------------------------------------------

import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Created: Jul 25, 2010 4:09:05 PM
 *
 * @param <E>
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class PriorityQueueStrict<E>
				extends PriorityQueueAbstract<E> {
	/**
	 * Variable <code>log</code> is a class logger.
	 */
	private static final Logger log = Logger.getLogger(PriorityQueueRelaxed.class
			.getName());

	//~--- fields ---------------------------------------------------------------

	private LinkedBlockingQueue<E>[] qs             = null;
	private int                      lowestNonEmpty = Integer.MAX_VALUE;

	//~--- constructors ---------------------------------------------------------

	/**
	 * Constructs ...
	 *
	 */
	public PriorityQueueStrict() {}

	/**
	 * Constructs ...
	 *
	 *
	 * @param maxPriority
	 * @param maxSize
	 */
	@SuppressWarnings({ "unchecked" })
	protected PriorityQueueStrict(int maxPriority, int maxSize) {
		init(maxPriority, maxSize);
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param maxPriority
	 * @param maxSize
	 */
	@Override
	@SuppressWarnings("unchecked")
	public final void init(int maxPriority, int maxSize) {

//  qs = new LinkedBlockingQueue[maxPriority + 1];
		// Setting a fixed priority to 3 which means all standard XMPP
		// packets go to the same queue, preventing packets reordering
		// 3 - SYSTEM, CLUSTER, HIGH
//    qs = new LinkedBlockingQueue[maxPriority];
		qs = new LinkedBlockingQueue[3];
		for (int i = 0; i < qs.length; i++) {
			qs[i] = new LinkedBlockingQueue<E>(maxSize);
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param element
	 * @param priority
	 *
	 *
	 *
	 * @return a value of boolean
	 */
	@Override
	public boolean offer(E element, int priority) {
		try {

			// return add(element, priority, false, owner);
			return add(element, priority, false);
		} catch (InterruptedException e) {
			log.warning("This should not happen, this is non-blocking operation.");

			return false;
		}
	}

	// public void put(E element, int priority, String owner) throws InterruptedException {

	/**
	 * Method description
	 *
	 *
	 * @param element
	 * @param priority
	 *
	 * @throws InterruptedException
	 */
	@Override
	public void put(E element, int priority) throws InterruptedException {

		// add(element, priority, true, owner);
		add(element, priority, true);
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of int[]
	 */
	@Override
	public int[] size() {
		int[] result = new int[qs.length];

		for (int i = 0; i < result.length; i++) {
			result[i] = qs[i].size();
		}

		return result;
	}

	// public E take(String owner) throws InterruptedException {

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 *
	 * @return a value of E
	 * @throws InterruptedException
	 */
	@Override
	public E take() throws InterruptedException {
		E e = null;

		while (e == null) {
			synchronized (this) {
				try {

					// Safeguard for "java wait spurious wakeup", google for it
					// if you don't know it. I have just learned about it too...
					while (lowestNonEmpty == Integer.MAX_VALUE) {

//          log.finest("[" + owner + "] waiting...");
						this.wait();
					}
				} catch (InterruptedException ex) {}

				LinkedBlockingQueue<E> q = qs[lowestNonEmpty];

				// log.finest("" + lowestNonEmpty + " taking from queue: ");
				e = q.poll();

//      if (e != null) {
//        log.finest("[" + owner + "] " + lowestNonEmpty + " element read: " + e.toString());
//      } else {
//        log.finest("[" + owner + "] " + lowestNonEmpty + " NULL element read!");
//      }
				if ((e == null) || q.isEmpty()) {
					lowestNonEmpty = findNextNonEmpty();

//        log.finest("[" + owner + "] " + "new lowestNonEmpty: " + lowestNonEmpty);
				}
			}
		}

		return e;
	}

	/**
	 * Method description
	 *
	 *
	 *
	 *
	 * @return a value of int
	 */
	@Override
	public int totalSize() {
		int result = 0;

		for (int i = 0; i < qs.length; i++) {
			result += qs[i].size();
		}

		return result;
	}

	//~--- set methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param maxSize
	 */
	@Override
	public void setMaxSize(int maxSize) {
		for (int i = 0; i < qs.length; i++) {

			// We don't want to lose any data so the new size must
			// be enough to keep all exising elements
			LinkedBlockingQueue<E> oldQueue = qs[i];
			int                    newSize  = Math.max(oldQueue.size(), maxSize);

			qs[i] = new LinkedBlockingQueue<E>(newSize);
			oldQueue.drainTo(qs[i]);
		}
	}

	//~--- methods --------------------------------------------------------------

	// TODO: Reduce synchronization but be carefull many threads are writing
	// to queues at the same time.
	// private boolean add(E element, int priority, boolean blocking, String owner)
	private boolean add(E element, int pr, boolean blocking) throws InterruptedException {
		int priority = pr;

		if (priority < 0) {
			throw new IllegalArgumentException("parameter priority must be " +
					"between 0 and " + (qs.length - 1));
		}
		if (qs.length <= priority) {
			priority = qs.length - 1;
		}

		boolean                result = true;
		LinkedBlockingQueue<E> q      = qs[priority];

		if (blocking) {
			q.put(element);

//    log.finest("[" + owner + "] " + priority + " B element added: " + element.toString());
			// log.finest("" + priority + " B element added: " + element.toString());
		} else {
			result = q.offer(element);

			// log.finest("" + priority + " B element added: " + element.toString());
//    log.finest("[" + owner + "] " + priority + " NB element added: " +
//            element.toString() + ", result: " + result +
//            ", lowestNonEmpty: " + lowestNonEmpty + ", size: " + q.size());
		}
		if (result) {
			synchronized (this) {
				if (priority < lowestNonEmpty) {

//        log.finest("[" + owner + "] setting new priority from " +
//                lowestNonEmpty + ", to: " + priority);
					lowestNonEmpty = priority;
				}
				this.notify();
			}
		}

		return result;
	}

	private int findNextNonEmpty() {
		for (int i = 0; i < qs.length; i++) {
			if (!qs[i].isEmpty()) {
				return i;
			}
		}

		return Integer.MAX_VALUE;
	}
}


//~ Formatted in Tigase Code Convention on 13/08/28
