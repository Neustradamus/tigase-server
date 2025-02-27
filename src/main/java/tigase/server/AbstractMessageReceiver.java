/*
 * Tigase XMPP Server - The instant messaging server
 * Copyright (C) 2004 Tigase, Inc. (office@tigase.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.server;

import tigase.component.ScheduledTask;
import tigase.kernel.beans.Inject;
import tigase.kernel.beans.config.ConfigField;
import tigase.server.filters.PacketFiltersBean;
import tigase.stats.StatisticType;
import tigase.stats.StatisticsContainer;
import tigase.stats.StatisticsList;
import tigase.sys.TigaseRuntime;
import tigase.util.Algorithms;
import tigase.util.routing.PatternComparator;
import tigase.util.stringprep.TigaseStringprepException;
import tigase.util.workqueue.PriorityQueueAbstract;
import tigase.util.workqueue.PriorityQueueRelaxed;
import tigase.xmpp.jid.JID;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * This is an archetype for all classes processing user-level packets. The implementation is designed for a heavy
 * packets processing with internal queues and number of separate threads depending on number of CPUs. Extensions of the
 * class can process normall user packets and administrator packets via ad-hoc commands. Good examples of such
 * components are <code>MUC</code>, <code>PubSub</code>, <code>SessionManager</code>. <br> The class offers scripting
 * API for administrator ad-hoc commands. <br> By default it internally uses priority queues which in some rare cases
 * may lead to packets reordering. When this happens and it is unacceptable for the deployment non-priority queues can
 * be used. The queues size is limited and depends on the available memory size. <br> Packets are processed by
 * <code>processPacket(Packet packet)</code> method which is concurrently called from multiple threads.
 * <br>
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
*/
public abstract class AbstractMessageReceiver
		extends BasicComponent
		implements StatisticsContainer, MessageReceiver, PacketWriterWithTimeout {

	/**
	 * Configuration property key for setting incoming packets filters on the component level.
	 */
	public static final String INCOMING_FILTERS_PROP_KEY = "incoming-filters";

	/**
	 * Configuration property default vakue with a default incoming packet filter loaded by Tigase server. <br> This is
	 * a comma-separated list of classes which should be loaded as packet filters. The classes must implement
	 * <code>PacketFilterIfc</code> interface.
	 */
	public static final String INCOMING_FILTERS_PROP_VAL = "tigase.server.filters.PacketCounter";

	/**
	 * Configuration property key allowing to overwrite a default (memory size dependent) size for the component
	 * internal queues. By default the queue size is adjusted to the available memory size to avoid out of memory
	 * errors.
	 */
	public static final String MAX_QUEUE_SIZE_PROP_KEY = "max-queue-size";

	/**
	 * A default value for max queue size property. The value is calculated at the server startup time using following
	 * formula: <br> <code>Runtime.getRuntime().maxMemory() / 400000L</code> You can change the default queue size by
	 * setting a different value for the <code>MAX_QUEUE_SIZE_PROP_KEY</code> property in the server configuration.
	 */
	public static final Integer MAX_QUEUE_SIZE_PROP_VAL = new Long(
			Runtime.getRuntime().maxMemory() / 400000L).intValue();

	/**
	 * Configuration property key for setting outgoing packets filters on the component level. This is a comma-separated
	 * list of classes which should be loaded as packet filters. The classes must implement <code>PacketFilterIfc</code>
	 * interface.
	 */
	public static final String OUTGOING_FILTERS_PROP_KEY = "outgoing-filters";

	/**
	 * Configuration property default vakue with a default outgoing packet filter loaded by Tigase server. <br> This is
	 * a comma-separated list of classes which should be loaded as packet filters. The classes must implement
	 * <code>PacketFilterIfc</code> interface.
	 */
	public static final String OUTGOING_FILTERS_PROP_VAL = "tigase.server.filters.PacketCounter";

	public static final String PACKET_DELIVERY_RETRY_COUNT_PROP_KEY = "packet-delivery-retry-count";

	/**
	 * Configuration property key for setting number of threads used by component ScheduledExecutorService.
	 */
	public static final String SCHEDULER_THREADS_PROP_KEY = "scheduler-threads";

	/**
	 * Constant used in time calculation procedures. Indicates a second that is 1000 milliseconds.
	 */
	protected static final long SECOND = 1000;

	/**
	 * Constant used in time calculation procedures. Indicates a minute that is 60 <code>SECOND</code>s.
	 */
	protected static final long MINUTE = 60 * SECOND;

	/**
	 * Constant used in time calculation procedures. Indicates a hour that is 60 <code>MINUTE</code>s.
	 */
	protected static final long HOUR = 60 * MINUTE;

	// String added intentionally!!
	// Don't change to AbstractMessageReceiver.class.getName()

	private static final Logger log = Logger.getLogger("tigase.debug.AbstractMessageReceiver");

	// PriorityQueueAbstract.getPriorityQueue(pr_cache.length, maxQueueSize);
	@Inject
	private PacketFiltersBean.IncomingPacketFiltersBean incoming_filters;
	@Inject
	private PacketFiltersBean.OutgoingPacketFiltersBean outgoing_filters;
	// Array cache to speed processing up....
	private final Priority[] pr_cache = Priority.values();
	private final List<PriorityQueueAbstract<Packet>> out_queues = new ArrayList<PriorityQueueAbstract<Packet>>(
			pr_cache.length);
	private final List<PriorityQueueAbstract<Packet>> in_queues = new ArrayList<>(pr_cache.length);
	private final long[] processPacketTimings = new long[100];
	private final Set<Pattern> regexRoutings = new ConcurrentSkipListSet<Pattern>(new PatternComparator());
	private final ThreadFactory threadFactory = new ThreadFactory() {

		private final ThreadFactory internal = Executors.defaultThreadFactory();

		@Override
		public Thread newThread(Runnable r) {
			Thread th = internal.newThread(r);
			th.setName("scheduler_" + th.getName() + "-" + getName());
			return th;
		}

	};
	private final ConcurrentHashMap<String, PacketReceiverTaskIfc> waitingTasks = new ConcurrentHashMap<>(
			16, 0.75f, 4);
	protected int maxInQueueSize = MAX_QUEUE_SIZE_PROP_VAL;
	protected int maxOutQueueSize = MAX_QUEUE_SIZE_PROP_VAL;
	@ConfigField(desc = "Maximum size of internal queues", alias = "max-queue-size")
	protected int maxQueueSize = MAX_QUEUE_SIZE_PROP_VAL;
	private int in_queues_size = processingInThreads();
	private long last_hour_packets = 0;
	private long last_minute_packets = 0;
	private long last_second_packets = 0;
	private int out_queues_size = processingOutThreads();
	private QueueListener out_thread = null;
	@ConfigField(desc = "Packet delivery retry count", alias = PACKET_DELIVERY_RETRY_COUNT_PROP_KEY)
	private int packetDeliveryRetryCount = 15;
	private long packetId = 0;
	private long packets_per_hour = 0;
	private long packets_per_minute = 0;
	private long packets_per_second = 0;
	private MessageReceiver parent = null;
	private int pptIdx = 0;
	@ConfigField(desc = "Priority queue class", alias = "priority-queue-implementation")
	private Class<? extends PriorityQueueAbstract> priorityQueueClass = PriorityQueueRelaxed.class;
	// ~--- fields ---------------------------------------------------------------
	// private static final TigaseTracer tracer =
	// TigaseTracer.getTracer("abstract");
	@ConfigField(desc = "Number of threads processing incoming packages", alias = "processing-in-threads")
	private int processingInThreads = processingInThreads();
	@ConfigField(desc = "Number of threads processing outgoing packages", alias = "processing-out-threads")
	private int processingOutThreads = processingOutThreads();
	private ScheduledExecutorService receiverScheduler = null;
	private Timer receiverTasks = null;
	private String resourceForPacketWithTimeout = null;
	@Inject(nullAllowed = true)
	private Set<ScheduledTask> scheduledTasks;
	@ConfigField(desc = "Number of threads for scheduler", alias = SCHEDULER_THREADS_PROP_KEY)
	private int schedulerThreads_size = 1;
	/**
	 * Variable <code>statAddedMessagesEr</code> keeps counter of unsuccessfuly added messages due to queue overflow.
	 */
	private long statReceivedPacketsEr = 0;
	/**
	 * Variable <code>statAddedMessagesOk</code> keeps counter of successfuly added messages to queue.
	 */
	private long statReceivedPacketsOk = 0;
	private long statSentPacketsEr = 0;
	private long statSentPacketsOk = 0;
	private Queue<Runnable> tasksAwaitingReceiver = new LinkedList<>();
	private ArrayDeque<QueueListener> threadsQueueIn = null;
	private ArrayDeque<QueueListener> threadsQueueOut = null;

	private static final DecimalFormat df = new DecimalFormat("#0.00");

	/**
	 * Helper method used in statistics to find uneven distribution of packet processing across processing threads
	 *
	 * @param array is an resizable array of {@code QueueListener} containing all processing threads
	 *
	 * @return string containing average value, variance as well as comma separated list of outlier threads and number
	 * of processed packets; returns empty string if passed array is empty or null
	 */
	private static String calculateOutliers(ArrayDeque<AbstractMessageReceiver.QueueListener> array) {

		if (array == null || array.size() == 0) {
			return "";
		}

		long[] allNumbers = new long[array.size()];
		int idx = 0;
		long sum = 0;
		for (QueueListener queueListener : array) {
			sum += queueListener.packetCounter;
			allNumbers[idx++] = queueListener.packetCounter;
		}

		double mean = sum / allNumbers.length;

		double tmp_sum_variance = 0;
		for (long allNumber : allNumbers) {
			tmp_sum_variance += Math.pow(allNumber - mean, 2);
		}

		double deviation = Math.sqrt(tmp_sum_variance / allNumbers.length);

		List<String> outliers = new ArrayList<>();
		for (QueueListener queueListener : array) {
			if (Math.abs(queueListener.packetCounter - mean) > (2 * deviation)) {
				outliers.add(queueListener.getName() + ":" + queueListener.packetCounter + ":x" +
									 df.format(queueListener.packetCounter / mean));
			}
		}

		return "mean: " + df.format(mean) + ", deviation: " + df.format(deviation) +
				(!outliers.isEmpty() ? ", outliers: " + outliers.toString() : "");
	}

	public AbstractMessageReceiver() {
	}

	/**
	 * Method adds a <code>Packet</code> object to the internal input queue. Packets from the input queue are later
	 * passed to the <code>processPacket(Packet)</code> method. This is a blocking method waiting if necessary for the
	 * room if the queue is full. <br> The method returns a <code>boolean</code> value of <code>true</code> if the
	 * packet has been successfully added to the queue and <code>false</code> otherwise. <br> There can be many queues
	 * and many threads processing packets for the component, however the method makes the best effort to guarantee that
	 * packets are later processed in the correct order. For example that packets for a single user always end up in the
	 * same exact queue. You can tweak the packets distribution among threads by overwriting
	 * <code>hashCodeForPacket(Packet)</code> method.<br> If there is <code>N</code> threads the packets are distributed
	 * among thread using following logic:
	 * <br>
	 * <pre>
	 * int threadNo = Math.abs(hashCodeForPacket(packet) % N);
	 * </pre>
	 * <br>
	 * This is a preferred method to be used by most Tigase components. If the queues are full the component should stop
	 * and wait for more room. The blocking methods aim to prevent from the system overloading or wasting resources for
	 * generating packets which can't be processed anyway.
	 *
	 * @param packet is a <code>Packet</code> instance being put to the component internal input queue.
	 *
	 * @return a <code>boolean</code> value of <code>true</code> if the packet has been successfully added to the queue
	 * and <code>false</code> otherwise.
	 */
	@Override
	public boolean addPacket(Packet packet) {
		int queueIdx = Math.abs(hashCodeForPacket(packet) % in_queues_size);

		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "[{0}] queueIdx={1}, {2}",
					new Object[]{getName(), queueIdx, packet.toStringSecure()});
		}
		try {
			in_queues.get(queueIdx).put(packet, packet.getPriority().ordinal());
			++statReceivedPacketsOk;
		} catch (InterruptedException e) {
			++statReceivedPacketsEr;
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Packet dropped for unknown reason: {0}", packet);
			}

			return false;
		}    // end of try-catch

		return true;
	}

	/**
	 * This is a variant of <code>addPacket(Packet)</code> method which adds <code>Packet</code> to in the internal
	 * input queue without blocking. <br> The method returns a <code>boolean</code> value of <code>true</code> if the
	 * packet has been successful added to the queue and <code>false</code> otherwise. <br> Use of the non-blocking
	 * methods is not recommended for most of the components implementations. The only component which is allowed to use
	 * them is the server <code>MessageRouter</code> implementation which can not hang on any method. This would cause a
	 * dead-lock in the application. All other components must use blocking methods and wait if the system is under so
	 * high load that it's queues are full. <br> See <code>addPacket(Packet)</code> method's documentation for some more
	 * details.
	 *
	 * @param packet is a <code>Packet</code> instance being put to the component internal input queue.
	 *
	 * @return a <code>boolean</code> value of <code>true</code> if the packet has been successfully added to the queue
	 * and <code>false</code> otherwise.
	 *
	 * @see AbstractMessageReceiver#addPacket(Packet packet)
	 */
	@Override
	public boolean addPacketNB(Packet packet) {
		int queueIdx = Math.abs(hashCodeForPacket(packet) % in_queues_size);

		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "[{0}] queueIdx={1}, {2}",
					new Object[]{getName(), queueIdx, packet.toStringSecure()});
		}

		boolean result = in_queues.get(queueIdx).offer(packet, packet.getPriority().ordinal());

		if (result) {
			++statReceivedPacketsOk;
		} else {

			// Queue overflow!
			++statReceivedPacketsEr;
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Packet dropped due to queue overflow: {0}", packet);
			}
		}

		return result;
	}

	/**
	 * This is a convenience method for adding all packets stored in given queue to the component's internal input
	 * queue.<br> The method calls <code>addPacket(Packet)</code> in a loop for each packet in the queue. If the call
	 * returns <code>true</code> then the packet is removed from the given queue, otherwise the methods ends the loop
	 * and returns <code>false</code>. <br> Please note, if the method returns <code>true</code> it means that all the
	 * packets from the queue passed as a parameter have been successfuly run through the <code>addPacket(Packet)</code>
	 * method and the queue passed as a parameter should be empty. If the method returns false then at least one packet
	 * from the parameter queue wasn't successfully run through the <code>addPacket(Packet)</code> method. If the method
	 * returns <code>false</code> then the queue passed as a parameter is not empty and it contains packet which was
	 * unseccessfully run through the <code>addPacket(Packet)</code> method and all the packets which were not run at
	 * all.
	 *
	 * @param packets is a <code>Queue</code> of packets for adding to the component internal input queue. All the
	 * packets are later processed by <code>processPacket(Packet)</code> method in the same exact order if they are
	 * processed by the same thread. See documentation <code>hashCodeForPacket(Packet)</code> method how to control
	 * assiging packets to particular threads.
	 *
	 * @return a <code>boolean</code> value of <code>true</code> if all packets has been successfully added to the
	 * component's internal input queue and <code>false</code> otherwise.
	 *
	 * @see AbstractMessageReceiver#hashCodeForPacket(Packet packet)
	 */
	@Override
	public boolean addPackets(Queue<Packet> packets) {
		boolean result = true;
		Packet p = packets.peek();

		while (p != null) {
			result = addPacket(p);
			if (result) {
				packets.poll();
			} else {
				break;
			}    // end of if (result) else
			p = packets.peek();
		}      // end of while ()

		return result;
	}

	/**
	 * Method adds a new routing address for the component. Routing addresses are used by the <code>MessageRouter</code>
	 * to calculate packet's destination. If the packet's destination address matches one of the component's routing
	 * addresses the packet is added to the component's internal input queue. <br> By default all components accept
	 * packets addressed to the componentId and to:
	 * <br>
	 * <pre>
	 * component.getName() + '@' + any virtual domain
	 * </pre>
	 * <br>
	 * TODO: The future implementation most likely accept packets addressed to:
	 * <br>
	 * <pre>
	 * any virtual domain + '/' + component.getName()
	 * </pre>
	 * <br>
	 * instead. <br> The routings are passed as Java regular expression strings are the extra addresses accepted by the
	 * component. In most cases this is used by the external component protocol implementations which can dynamically
	 * change accepted addresses depending on the connected external components.
	 *
	 * @param address is a Java regular expression string for the packet's destination address accepted by this
	 * component.
	 */
	public void addRegexRouting(String address) {
		if (log.isLoggable(Level.FINE)) {
			log.log(Level.FINE, "{0} - attempt to add regex routing: {1}", new Object[]{getName(), address});
		}
		regexRoutings.add(Pattern.compile(address, Pattern.CASE_INSENSITIVE));
		if (log.isLoggable(Level.FINE)) {
			log.log(Level.FINE, "{0} - success adding regex routing: {1}", new Object[]{getName(), address});
		}
	}

	/**
	 * Method queues and executes timer tasks using ScheduledExecutorService which allows using more than one thread for
	 * executing tasks.
	 */
	public void addTimerTask(tigase.util.common.TimerTask task, long delay) {
		if (task.isCancelled()) {
			return;
		}
		if (receiverScheduler == null) {
			tasksAwaitingReceiver.offer(() -> this.addTimerTask(task, delay));
			return;
		}
		ScheduledFuture<?> future = receiverScheduler.schedule(task, delay, TimeUnit.MILLISECONDS);

		task.setScheduledFuture(future);
	}

	public void addTimerTask(tigase.util.common.TimerTask task, long initialDelay, long period) {
		if (task.isCancelled()) {
			return;
		}
		if (receiverScheduler == null) {
			tasksAwaitingReceiver.offer(() -> this.addTimerTask(task, initialDelay, period));
			return;
		}
		ScheduledFuture<?> future = receiverScheduler.scheduleAtFixedRate(task, initialDelay, period,
																		  TimeUnit.MILLISECONDS);
		task.setScheduledFuture(future);
	}

/**
	 * Method queues and executes timer tasks using ScheduledExecutorService which allows using more than one thread for
	 * executing tasks. It allows to set a timeout to cancel long running tasks
	 *
	 * @param task a task implementing {@link tigase.util.common.TimerTask}
	 * @param delay in milliseconds delay after which task will be started
	 * @param timeout in milliseconds after which task will be cancelled disregarding whether it has finished or not
	 */
	public void addTimerTaskWithTimeout(final tigase.util.common.TimerTask task, long delay, long timeout) {
		if (task.isCancelled()) {
			return;
		}
		if (receiverScheduler == null) {
			tasksAwaitingReceiver.offer(() -> this.addTimerTaskWithTimeout(task, delay, timeout));
			return;
		}
		receiverScheduler.schedule(new tigase.util.common.TimerTask() {
			@Override
			public void run() {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Cancelling tigase task (timeout): " + task);
				}
				if (task != null) {
					task.cancel(true);
				}
			}
		}, timeout, TimeUnit.MILLISECONDS);

		addTimerTask(task, delay);
	}

	/**
	 * Creates and executes a periodic action that becomes enabled first after the given initial delay, and subsequently
	 * with the given period; please refer to {@link ScheduledExecutorService#scheduleAtFixedRate} javadoc for details.
	 * It utilizes Tigase {@link tigase.util.common.TimerTask} and allows setting a timeout to cancel long running
	 * tasks
	 *
	 * @param task a task implementing {@link tigase.util.common.TimerTask}
	 * @param delay in milliseconds, the time to delay first execution
	 * @param period in milliseconds, the period between successive executions
	 * @param timeout in milliseconds after which task will be cancelled disregarding whether it has finished or not
	 */
	public void addTimerTaskWithTimeout(final tigase.util.common.TimerTask task, long delay, long period,
										long timeout) {
		if (task.isCancelled()) {
			return;
		}
		if (receiverScheduler == null) {
			tasksAwaitingReceiver.offer(() -> this.addTimerTaskWithTimeout(task, delay, period, timeout));
			return;
		}
		receiverScheduler.schedule(new tigase.util.common.TimerTask() {
			@Override
			public void run() {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Cancelling tigase task (timeout): " + task);
				}
				if (task != null) {
					task.cancel(true);
				}
			}
		}, timeout, TimeUnit.MILLISECONDS);

		addTimerTask(task, delay, period);
	}

	/**
	 * Method clears, removes all the component routing addresses. After this method call the component accepts only
	 * packets addressed to default routings that is component ID or the component name + '@' + virtual domains
	 */
	public void clearRegexRoutings() {
		regexRoutings.clear();
	}

	/**
	 * Utility method executed precisely every hour. A component can overwrite the method to put own code to be executed
	 * at the regular intervals of time. <br> Note, no extensive calculations should happen in this method nor long
	 * lasting operations. It is essential that the method processing does not exceed 1 hour. The overriding method must
	 * call the the super method first and only then run own code.
	 */
	@Override
	public synchronized void everyHour() {
		packets_per_hour = statReceivedPacketsOk - last_hour_packets;
		last_hour_packets = statReceivedPacketsOk;
		super.everyHour();
	}

	/**
	 * Utility method executed precisely every minute. A component can overwrite the method to put own code to be
	 * executed at the regular intervals of time. <br> Note, no extensive calculations should happen in this method nor
	 * long lasting operations. It is essential that the method processing does not exceed 1 minute. The overriding
	 * method must call the the super method first and only then run own code.
	 */
	@Override
	public synchronized void everyMinute() {
		packets_per_minute = statReceivedPacketsOk - last_minute_packets;
		last_minute_packets = statReceivedPacketsOk;
		receiverTasks.purge();
		super.everyMinute();
	}

	/**
	 * Utility method executed precisely every second. A component can overwrite the method to put own code to be
	 * executed at the regular intervals of time. <br> Note, no extensive calculations should happen in this method nor
	 * long lasting operations. It is essential that the method processing does not exceed 1 second. The overriding
	 * method must call the the super method first and only then run own code.
	 */
	@Override
	public synchronized void everySecond() {
		packets_per_second = statReceivedPacketsOk - last_second_packets;
		last_second_packets = statReceivedPacketsOk;
		super.everySecond();
	}

	/**
	 * This method decides how incoming packets are distributed among processing threads. Different components needs
	 * different distribution to efficient use all threads and avoid packets re-ordering. <br> If there are N processing
	 * threads, packets are distributed among threads using following code:
	 * <br>
	 * <pre>
	 * int threadNo = Math.abs(hashCodeForPacket(packet) % N);
	 * </pre>
	 * <br>
	 * For a PubSub component, for example, a better packets distribution would be based on the PubSub channel name, for
	 * SM a better distribution is based on the destination address, etc....
	 *
	 * @param packet is a <code>Packet</code> which needs to be processed by some thread.
	 *
	 * @return a hash code generated for the input thread.
	 */
	public int hashCodeForPacket(Packet packet) {
		// use of getPacketFrom was moved to SM as it worked OK only in use case of SM 
		// where packet may came from connection manager
		if ((packet.getPacketTo() != null) && !getComponentId().equals(packet.getPacketTo())) {
			return packet.getPacketTo().hashCode();
		}

		// If not, then a better way is to get hashCode from the elemTo address
		// as this would be by the destination address user name:
		if (packet.getStanzaTo() != null) {
			return packet.getStanzaTo().getBareJID().hashCode();
		}

		return 1;
	}

	public String newPacketId(String prefix) {
		final String uuid = UUID.randomUUID().toString();
		return prefix != null ? prefix + "-" + uuid : uuid;
	}

	/**
	 * Concurrency control method. Returns preferable number of threads set for this component.
	 *
	 * @return preferable number of threads set for this component.
	 */
	public int processingInThreads() {
		return 1;
	}

	/**
	 * Concurrency control method. Returns preferable number of threads set for this component.
	 *
	 * @return preferable number of threads set for this component.
	 */
	public int processingOutThreads() {
		return 1;
	}
	/**
	 * By default this method just copies the given packet between queue. This method operates on packets which have
	 * been already processed somehow by the component so usually the default action is the best one, however some
	 * components in rare cases may choose to process packets differently. In most cases this method should not be
	 * overridden.
	 *
	 * @param packet is an output packet which normally has to go to other component for further processing.
	 */
	public void processOutPacket(Packet packet) {
		if (parent != null) {
			parent.addPacket(packet);
		} else {

			// It may happen for MessageRouter and this is intentional
			addPacketNB(packet);

			// log.warning("[" + getName() + "]  " + "No parent!");
		}    // end of else
	}

	/**
	 * This is the main <code>Packet</code> processing method. It is called concurrently from many threads so
	 * implementing it in thread save manner is essential. The method is called for each packet addressed to the
	 * component. <br> Please note, the <code>Packet</code> instance may be processed by different parts of the server,
	 * different components or plugins at the same time. Therefore this is very important to tread the
	 * <code>Packet</code> instance as unmodifiable object. <br> Processing in this method is asynchronous, therefore
	 * there is no result value. If there are some 'result' packets generated during processing, they should be passed
	 * back using <code>addOutPacket(Packet)</code> method.
	 *
	 * @param packet is an instance of the <code>Packet</code> class passed for processing.
	 */
	public abstract void processPacket(Packet packet);

	@Override
	public final void processPacket(final Packet packet, final Queue<Packet> results) {
		addPacketNB(packet);
	}

	@Override
	public void release() {
		stop();
	}

	public boolean removeRegexRouting(String address) {
		return regexRoutings.remove(Pattern.compile(address, Pattern.CASE_INSENSITIVE));
	}

	/**
	 * Method returns default number of threads used by SchedulerExecutorService
	 *
	 * @return a value of <code>int</code>
	 */
	public int schedulerThreads() {
		return 2;
	}

	@Override
	public void start() {
		if (log.isLoggable(Level.FINER)) {
			log.log(Level.INFO, "{0}: starting queue management threads ...", getName());
		}
		startThreads();

		if (scheduledTasks != null) {
			for (ScheduledTask task : scheduledTasks) {
				task.initialize();
			}
		}
	}

	public void stop() {
		if (log.isLoggable(Level.FINER)) {
			log.log(Level.INFO, "{0}: stopping queue management threads ...", getName());
		}
		stopThreads();
	}

	/**
	 * Method returns a <code>Set</code> with all component's routings as a compiled regular expression patterns. The
	 * <code>Set</code> can be empty but it can not be null.
	 *
	 * @return a <code>Set</code> with all component's routings as a compiled regular expression patterns.
	 */
	public Set<Pattern> getRegexRoutings() {
		return regexRoutings;
	}

	/**
	 * Method returns component statistics. Please note, the method can be called every second by the server monitoring
	 * system therefore no extensive or lengthy calculations are allowed. If there are some statistics requiring lengthy
	 * operations like database access they must have <code>Level.FINEST</code> assigned and must be put inside the
	 * level guard to prevent generating them by the system monitor. The system monitor does not collect
	 * <code>FINEST</code> statistics. <br> Level guard code looks like the example below:
	 * <br>
	 * <pre>
	 * if (list.checkLevel(Level.FINEST)) {
	 *   // Some CPU intensive calculations or lengthy operations
	 *   list.add(getName(), "Statistic description", stat_value, Level.FINEST);
	 * }
	 *
	 * </pre>
	 * This way you make sure your extensive operation is not executed every second by the monitoring system and does
	 * not affect the server performance.
	 *
	 * @param list is a <code>StatistcsList</code> where all statistics are stored.
	 */
	@Override
	public void getStatistics(StatisticsList list) {
		list.add(getName(), "Last second packets", packets_per_second, Level.FINE);
		list.add(getName(), "Last minute packets", packets_per_minute, Level.FINE);
		list.add(getName(), "Last hour packets", packets_per_hour, Level.FINE);
		list.add(getName(), "Processing threads", processingInThreads(), Level.FINER);
		list.add(getName(), StatisticType.MSG_RECEIVED_OK.getDescription(), statReceivedPacketsOk, Level.FINE);
		list.add(getName(), StatisticType.MSG_SENT_OK.getDescription(), statSentPacketsOk, Level.FINE);
		if (list.checkLevel(Level.FINEST)) {
			int[] in_priority_sizes = in_queues.get(0).size();

			for (int i = 1; i < in_queues.size(); i++) {
				int[] tmp_pr_sizes = in_queues.get(i).size();

				for (int j = 0; j < tmp_pr_sizes.length; j++) {
					in_priority_sizes[j] += tmp_pr_sizes[j];
				}
			}

			int[] out_priority_sizes = out_queues.get(0).size();

			for (int i = 1; i < out_queues.size(); i++) {
				int[] tmp_pr_sizes = out_queues.get(i).size();

				for (int j = 0; j < tmp_pr_sizes.length; j++) {
					out_priority_sizes[j] += tmp_pr_sizes[j];
				}
			}
			for (int i = 0; i < in_priority_sizes.length; i++) {
				Priority queue = Priority.values()[i];

				list.add(getName(), "In queue wait: " + queue.name(), in_priority_sizes[queue.ordinal()], Level.FINEST);
			}
			for (int i = 0; i < out_priority_sizes.length; i++) {
				Priority queue = Priority.values()[i];

				list.add(getName(), "Out queue wait: " + queue.name(), out_priority_sizes[queue.ordinal()],
						 Level.FINEST);
			}
		}

		int in_queue_size = 0;

		for (PriorityQueueAbstract<Packet> total_size : in_queues) {
			in_queue_size += total_size.totalSize();
		}

		int out_queue_size = 0;

		for (PriorityQueueAbstract<Packet> total_size : out_queues) {
			out_queue_size += total_size.totalSize();
		}
		list.add(getName(), "Total In queues wait", in_queue_size, Level.INFO);
		list.add(getName(), "Total Out queues wait", out_queue_size, Level.INFO);
		list.add(getName(), "Total queues wait", (in_queue_size + out_queue_size), Level.INFO);
		list.add(getName(), StatisticType.MAX_QUEUE_SIZE.getDescription(), (maxInQueueSize * processingInThreads()),
				 Level.FINEST);
		list.add(getName(), StatisticType.IN_QUEUE_OVERFLOW.getDescription(), statReceivedPacketsEr, Level.INFO);
		list.add(getName(), StatisticType.OUT_QUEUE_OVERFLOW.getDescription(), statSentPacketsEr, Level.INFO);
		list.add(getName(), "Total queues overflow", (statReceivedPacketsEr + statSentPacketsEr), Level.INFO);

		long res = 0;

		for (long ppt : processPacketTimings) {
			res += ppt;
		}

		long prcessingTime = res / processPacketTimings.length;

		list.add(getName(), "Average processing time on last " + processPacketTimings.length + " runs [ms]",
				 prcessingTime, Level.FINE);
		for (PacketFilterIfc packetFilter : incoming_filters.getFilters()) {
			packetFilter.getStatistics(list);
		}
		for (PacketFilterIfc packetFilter : outgoing_filters.getFilters()) {
			packetFilter.getStatistics(list);
		}
		if (list.checkLevel(Level.FINEST)) {
			list.add(getName(), "Processed packets thread IN", threadsQueueIn.toString(), Level.FINEST);
			list.add(getName(), "Processed packets thread OUT", threadsQueueOut.toString(), Level.FINEST);
			list.add(getName(), "Processed packets thread (outliers) IN", calculateOutliers(threadsQueueIn),
					 Level.FINEST);
			list.add(getName(), "Processed packets thread (outliers) OUT", calculateOutliers(threadsQueueOut),
					 Level.FINEST);
		}
		super.getStatistics(list);
	}

	@Override
	public boolean isInRegexRoutings(String address) {

		// log.finest(getName() + " looking for regex routings: " + address);
		for (Pattern pat : regexRoutings) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "{0} matching: {1} against {2}",
						new Object[]{getName(), address, pat.toString()});
			}
			if (pat.matcher(address).matches()) {
				return true;
			}

			// log.finest(getName() + " matching failed against pattern: " +
			// pat.toString());
		}

		return false;
	}

	public void setIncoming_filters(PacketFiltersBean.IncomingPacketFiltersBean incoming_filters) {
		this.incoming_filters = incoming_filters;
		updateFiltersName();
	}

	public void setOutgoing_filters(PacketFiltersBean.OutgoingPacketFiltersBean outgoing_filters) {
		this.outgoing_filters = outgoing_filters;
		updateFiltersName();
	}

	protected void updateFiltersName() {
		Optional.ofNullable(incoming_filters).ifPresent(filters -> filters.setName(getName()));
		Optional.ofNullable(outgoing_filters).ifPresent(filters -> filters.setName(getName()));
	}

	@Override
	public void beanConfigurationChanged(Collection<String> changedFields) {
		super.beanConfigurationChanged(changedFields);

		boolean recreate = ((maxInQueueSize != (maxQueueSize / processingInThreads) * 2) ||
				(maxOutQueueSize != (maxQueueSize / processingOutThreads) * 2));

		if (in_queues.isEmpty() || !priorityQueueClass.equals(in_queues.get(0).getClass())) {
			recreate = true;
			this.in_queues.clear();
			this.out_queues.clear();
		}

		if (processingInThreads != in_queues_size || processingOutThreads == out_queues_size) {
			recreate = true;
			this.in_queues_size = processingInThreads;
			this.out_queues_size = processingOutThreads;
			this.in_queues.clear();
			this.out_queues.clear();
		}

		if (recreate) {
			recreateProcessingQueues(maxQueueSize);
		}
	}

	@Override
	public void setCompId(JID jid) {
		super.setCompId(jid);
		if (jid != null) {
			resourceForPacketWithTimeout = Algorithms.sha256(jid.getDomain());
		} else {
			resourceForPacketWithTimeout = null;
		}
	}

	@Override
	public void setName(String name) {
		super.setName(name);
		in_queues_size = processingInThreads();
		out_queues_size = processingOutThreads();
		schedulerThreads_size = schedulerThreads();
		updateFiltersName();
	}

	@Override
	public void setParent(MessageReceiver parent) {
		this.parent = parent;
	}

	public void setSchedulerThreads_size(int size) {
		log.log(Level.FINE, "Setting scheduler size to: {0}", new Object[]{size});
		if (schedulerThreads_size != size) {
			this.schedulerThreads_size = size;

			ScheduledExecutorService scheduler = receiverScheduler;
			receiverScheduler = Executors.newScheduledThreadPool(size, threadFactory);
			scheduler.shutdown();
		}
	}

	public boolean addOutPacketWithTimeout(Packet packet, ReceiverTimeoutHandler handler, long delay, TimeUnit unit) {

		// It is automatically added to collections and the Timer
		new PacketReceiverTask(handler, delay, unit, packet);

		return addOutPacket(packet);
	}

	public boolean addOutPacketWithTimeout(Packet packet, Duration timeout, PacketWriterWithTimeout.Handler handler) {
		new SimplePacketReceiverTask(handler, timeout, packet);
		return addOutPacket(packet);
	}
	
	protected boolean addOutPacket(Packet packet) {
		int queueIdx = Math.abs(hashCodeForPacket(packet) % out_queues_size);

		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "[{0}]  queueIdx={1}, {2}",
					new Object[]{getName(), queueIdx, packet.toStringSecure()});
		}
		try {
			out_queues.get(queueIdx).put(packet, packet.getPriority().ordinal());
			++statSentPacketsOk;
		} catch (InterruptedException e) {
			++statSentPacketsEr;
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Packet dropped for unknown reason: {0}", packet);
			}

			return false;
		}    // end of try-catch

		return true;
	}

	/**
	 * Non blocking version of <code>addOutPacket</code>.
	 *
	 * @param packet a <code>Packet</code> value
	 *
	 * @return a <code>boolean</code> value
	 */
	protected boolean addOutPacketNB(Packet packet) {
		int queueIdx = Math.abs(hashCodeForPacket(packet) % out_queues_size);

		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "[{0}]  queueIdx={1}, {2}",
					new Object[]{getName(), queueIdx, packet.toStringSecure()});
		}

		boolean result = false;

		result = out_queues.get(queueIdx).offer(packet, packet.getPriority().ordinal());
		if (result) {
			++statSentPacketsOk;
		} else {

			// Queue overflow!
			++statSentPacketsEr;
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Packet dropped due to queue overflow: {0}", packet);
			}
		}

		return result;
	}

	protected boolean addOutPackets(Queue<Packet> packets) {
		Packet p = null;
		boolean result = true;

		while ((p = packets.peek()) != null) {
			result = addOutPacket(p);
			if (result) {
				packets.poll();
			} else {
				return false;
			}    // end of if (result) else
		}      // end of while ()

		return true;
	}

	/**
	 * Method queues and executes timer tasks using ScheduledExecutorService which allows using more than one thread for
	 * executing tasks.
	 */
	protected void addTimerTask(tigase.util.common.TimerTask task, long delay, TimeUnit unit) {
		if (task.isCancelled()) {
			return;
		}
		if (receiverScheduler == null) {
			tasksAwaitingReceiver.offer(() -> this.addTimerTask(task, delay, unit));
			return;
		}
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "adding timer, task: {0}, delay: {1}, TimeUnit: {2}, receiverScheduler: {3}",
					new Object[]{task, delay, unit, receiverScheduler});
		}
		ScheduledFuture<?> future = receiverScheduler.schedule(task, delay, unit);

		task.setScheduledFuture(future);
	}

	protected Integer getMaxQueueSize(int def) {
		return def;
	}

	private void recreateProcessingQueues(int maxQueueSize) {
		// Processing threads number is split to incoming and outgoing queues...
		// So real processing threads number of in_queues is processingThreads()/2
		this.maxInQueueSize = (maxQueueSize / processingInThreads) * 2;
		this.maxOutQueueSize = (maxQueueSize / processingOutThreads) * 2;
		log.log(Level.FINEST, "{0} maxQueueSize: {1}, maxInQueueSize: {2}, maxOutQueueSize: {3}",
				new Object[]{getName(), maxQueueSize, maxInQueueSize, maxOutQueueSize});
		log.log(Level.FINEST, "{0} maxQueueSize: {1}, maxInQueueSize: {2}, maxOutQueueSize: {3}",
				new Object[]{getName(), maxQueueSize, maxInQueueSize, maxOutQueueSize});

		if (maxInQueueSize <= 15 || maxQueueSize <= 15) {
			TigaseRuntime.getTigaseRuntime()
					.shutdownTigase(new String[]{"You configured component of class " + getClass().getCanonicalName() +
														 " with packet queues total size set to " + maxQueueSize,
												 "Component uses " +
														 Math.max(processingInThreads, processingOutThreads) +
														 " queues which would give a limit of " +
														 Math.min(maxInQueueSize, maxOutQueueSize) +
														 " packets per queue.",
												 "This value is too low for Tigase XMPP Server to run properly. Please adjust the configuration of the server.",
												 "max-queue-size should be set to at least " +
														 (Math.max(processingInThreads, processingOutThreads) / 2) *
																 16});
		}

		if (in_queues.size() == 0) {
			for (int i = 0; i < in_queues_size; i++) {
				PriorityQueueAbstract<Packet> queue = PriorityQueueAbstract.getPriorityQueue(pr_cache.length,
																							 maxInQueueSize,
																							 priorityQueueClass);

				in_queues.add(queue);
			}
		} else {
			for (int i = 0; i < in_queues.size(); i++) {
				in_queues.get(i).setMaxSize(maxInQueueSize);
			}
		}
		if (out_queues.size() == 0) {
			for (int i = 0; i < out_queues_size; i++) {
				PriorityQueueAbstract<Packet> queue = PriorityQueueAbstract.getPriorityQueue(pr_cache.length,
																							 maxOutQueueSize,
																							 priorityQueueClass);

				out_queues.add(queue);
			}
		} else {
			for (int i = 0; i < out_queues.size(); i++) {
				out_queues.get(i).setMaxSize(maxOutQueueSize);
			}
		}
	}

	private Packet filterPacket(Packet packet, List<PacketFilterIfc> filters) {
		Packet result = packet;

		for (PacketFilterIfc packetFilterIfc : filters) {
			result = packetFilterIfc.filter(result);
			if (result == null) {
				break;
			}
		}

		return result;
	}

	private void startThreads() {
		if (log.isLoggable(Level.CONFIG)) {
			log.log(Level.CONFIG,
					"Starting threads, in_queues_size: {0}, out_queues_size: {1}, schedulerThreads_size: {2}",
					new Object[]{in_queues_size, out_queues_size, schedulerThreads_size});
		}
		if (threadsQueueIn == null) {
			threadsQueueIn = new ArrayDeque<>(8);
			for (int i = 0; i < in_queues_size; i++) {
				QueueListener in_thread = new QueueListener(in_queues.get(i), QueueType.IN_QUEUE);

				in_thread.setName("in_" + i + "-" + getName());
				in_thread.start();
				threadsQueueIn.add(in_thread);
			}
		}
		if (threadsQueueOut == null) {
			threadsQueueOut = new ArrayDeque<>(8);
			for (int i = 0; i < out_queues_size; i++) {
				QueueListener out_thread = new QueueListener(out_queues.get(i), QueueType.OUT_QUEUE);

				out_thread.setName("out_" + i + "-" + getName());
				out_thread.start();
				threadsQueueOut.add(out_thread);
			}
		}    // end of if (thread == null || ! thread.isAlive())

		// if ((out_thread == null) ||!out_thread.isAlive()) {
		// out_thread = new QueueListener(out_queue, QueueType.OUT_QUEUE);
		// out_thread.setName("out_" + getName());
		// out_thread.start();
		// } // end of if (thread == null || ! thread.isAlive())
		receiverScheduler = Executors.newScheduledThreadPool(schedulerThreads_size, threadFactory);
		receiverTasks = new Timer(getName() + " tasks", true);
		receiverTasks.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				everySecond();
			}
		}, SECOND, SECOND);
		receiverTasks.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				everyMinute();
			}
		}, MINUTE, MINUTE);
		receiverTasks.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				everyHour();
			}
		}, HOUR, HOUR);

		Runnable task;
		while ((task = tasksAwaitingReceiver.poll()) != null) {
			task.run();
		}
	}

	private void stopThreads() {
		// stopped = true;
		try {
			ArrayDeque<QueueListener> threads = new ArrayDeque<>();
			if (threadsQueueIn != null) {
				threads.addAll(threadsQueueIn);
			}
			if (threadsQueueOut != null) {
				threads.addAll(threadsQueueOut);
			}
			if (out_thread != null) {
				threads.add(out_thread);
			}
			stopThread(threads);

		} catch (InterruptedException e) {
		}
		threadsQueueIn = null;
		threadsQueueOut = null;
		out_thread = null;
		if (receiverTasks != null) {
			receiverTasks.cancel();
			receiverTasks = null;
		}
		if (receiverScheduler != null) {
			receiverScheduler.shutdownNow();
			receiverScheduler = null;
		}
	}

	private void stopThread(ArrayDeque<QueueListener> threadsQueue) throws InterruptedException {
		if (threadsQueue != null) {
			Set<QueueListener> awaiting = new HashSet<>();
			for (QueueListener in_thread : threadsQueue) {
				in_thread.threadStopped = true;
				in_thread.interrupt();
				awaiting.add(in_thread);
			}
			while (!awaiting.isEmpty()) {
				for (QueueListener in_thread : threadsQueue) {
					if (!in_thread.isAlive()) {
						awaiting.remove(in_thread);
					}
				}
				Thread.sleep(10);
			}
		}
	}

	private interface PacketReceiverTaskIfc {

		void handleResponse(Packet response);

		void handleTimeout();

	}
	
	protected String getResourceForPacketWithTimeout() {
		return resourceForPacketWithTimeout;
	}

	private class SimplePacketReceiverTask extends tigase.util.common.TimerTask implements PacketReceiverTaskIfc {

		private final PacketWriterWithTimeout.Handler handler;
		private final String id;

		SimplePacketReceiverTask(PacketWriterWithTimeout.Handler handler, Duration timeout, Packet packet) {
			this.handler = handler;

			JID from = packet.getStanzaFrom();
			if (from.getResource() == null && isLocalDomainOrComponent(from.toString())) {
				from = from.copyWithResourceNS(getResourceForPacketWithTimeout());
			}

			if (packet.getStanzaId() != null) {
				throw new IllegalArgumentException("Packet cannot have `id` set as it is required to be unique and will be set internally.");
			}
			packet.getElement().setAttribute("id", UUID.randomUUID().toString());

			packet.initVars(from, packet.getStanzaTo());

			// we need to generate id which will make this packet routable in the cluster!
			this.id = packet.getStanzaFrom().toString() + packet.getStanzaId();

			waitingTasks.put(id, this);

			addTimerTask(this, timeout.getSeconds(), TimeUnit.SECONDS);
		}

		public void handleResponse(Packet response) {
			cancel();
			this.handler.handle(response);
		}

		public void handleTimeout() {
			waitingTasks.remove(id);
			this.handler.handle(null);
		}

		@Override
		public void run() {
			handleTimeout();
		}
		
	}

	private class PacketReceiverTask
			extends tigase.util.common.TimerTask implements PacketReceiverTaskIfc {

		private ReceiverTimeoutHandler handler = null;
		private String id = null;
		private Packet packet = null;
		private int retryCount = packetDeliveryRetryCount;

		private PacketReceiverTask(ReceiverTimeoutHandler handler, long delay, TimeUnit unit, Packet packet) {
			super();
			this.handler = handler;
			this.packet = packet;
			this.id = packet.getFrom().toString() + packet.getStanzaId();

			String countStr = packet.getElement().getAttributeStaticStr("retryCount");
			if (countStr != null) {
				retryCount = Byte.valueOf(countStr);
				retryCount--;
			}
			this.packet.getElement().setAttribute("retryCount", Integer.toString(retryCount));

			if (retryCount < 0) {
				if (log.isLoggable(Level.WARNING)) {
					log.log(Level.WARNING,
							"Dropping command packet! Retry limit reached for packet with ID: {0}, retryCount: {1}, packet: {2}",
							new Object[]{id, retryCount, this.packet});
				}
				PacketReceiverTaskIfc remove = waitingTasks.remove(id);
				if (remove instanceof tigase.util.common.TimerTask) {
					((tigase.util.common.TimerTask) remove).cancel();
				}
				return;
			}

			String delayStr = packet.getElement().getAttributeStaticStr("delay");
			if (delayStr != null) {
				delay = (long) (Long.valueOf(delayStr) * 1.5);
			}

			this.packet.getElement().setAttribute("delay", Long.toString(delay));

			waitingTasks.put(id, this);

			addTimerTask(this, delay, unit);

			try {
				this.packet.initVars();
			} catch (TigaseStringprepException e) {
				log.log(Level.WARNING, "Reinitializing packet failed", e);
			}

			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "[{0}] Added timeout task for ID: {1}, delay: {2}, retryCount: {3}, packet: {4}",
						new Object[]{getName(), id, delay, retryCount, this.packet});
			}
		}

		public void handleResponse(Packet response) {
			this.cancel();

			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "[{0}] Response received for id: {1}", new Object[]{getName(), id});
			}
			handler.responseReceived(packet, response);
		}

		public void handleTimeout() {

			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "[{0}] Fired timeout for id: {1}", new Object[]{getName(), id});
			}
			waitingTasks.remove(id);
			handler.timeOutExpired(packet);
		}

		@Override
		public void run() {
			handleTimeout();
		}
	}

	private class QueueListener
			extends Thread {

		private String compName = null;
		private long packetCounter = 0;
		private PriorityQueueAbstract<Packet> queue;
		private boolean threadStopped = false;
		private QueueType type = null;

		private QueueListener(PriorityQueueAbstract<Packet> q, QueueType type) {
			this.queue = q;
			this.type = type;
			compName = AbstractMessageReceiver.this.getName();
		}

		@Override
		public void run() {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "{0} starting queue processing.", getName());
			}

			Packet packet = null;
			Queue<Packet> results = new ArrayDeque<Packet>(2);

			while (!threadStopped) {
				try {

					// Now process next waiting packet
					// log.finest("[" + getName() + "] before take... " + type);
					// packet = queue.take(getName() + ":" + type);
					packet = queue.take();
					++packetCounter;

					// if (log.isLoggable(Level.INFO)) {
					// log.info("[" + getName() + "] packet from " + type + " queue: " +
					// packet);
					// }
					switch (type) {
						case IN_QUEUE:
							long startPPT = System.currentTimeMillis();

							// tracer.trace(null, packet.getElemTo(), packet.getElemFrom(),
							// packet.getFrom(), getName(), type.name(), null, packet);
							PacketReceiverTaskIfc task = null;

							if (packet.getTo() != null) {
								String id = packet.getTo().toString() + packet.getStanzaId();

								task = waitingTasks.remove(id);
							}
							if (task == null && packet.getStanzaTo() != null) {
								String id = packet.getStanzaTo().toString() + packet.getStanzaId();

								task = waitingTasks.remove(id);
							}
							if (task != null) {
								task.handleResponse(packet);
							} else {

								// log.finest("[" + getName() + "]  " +
								// "No task found for id: " + id);
								// Maybe this is a command for local processing...
								boolean processed = false;

								if (packet.isCommand() && (packet.getStanzaTo() != null) &&
										compName.equals(packet.getStanzaTo().getLocalpart()) &&
										isLocalDomain(packet.getStanzaTo().getDomain())) {
									processed = processScriptCommand(packet, results);
									if (processed) {
										Packet result = null;

										while ((result = results.poll()) != null) {
											addOutPacket(result);
										}
									}
								}
								if (!processed && ((packet = filterPacket(packet, incoming_filters.getFilters())) != null)) {
									processPacket(packet);
								}

								// It is all concurrent so we have to use a local index variable
								int idx = pptIdx;

								pptIdx = (pptIdx + 1) % processPacketTimings.length;

								long timing = System.currentTimeMillis() - startPPT;

								processPacketTimings[idx] = timing;
							}

							break;

						case OUT_QUEUE:

							// tracer.trace(null, packet.getElemTo(), packet.getElemFrom(),
							// packet.getTo(), getName(), type.name(), null, packet);
							if ((packet = filterPacket(packet, outgoing_filters.getFilters())) != null) {
								processOutPacket(packet);
							}

							break;

						default:
							log.log(Level.SEVERE, "Unknown queue element type: {0}", type);

							break;
					}    // end of switch (qel.type)
				} catch (InterruptedException e) {
					System.out.println("interrupted " + getName());
					// log.log(Level.SEVERE, "Exception during packet processing: ", e);
					// stopped = true;
				} catch (Exception e) {
					if (!threadStopped) {
						log.log(Level.SEVERE, "[" + getName() + "] Exception during packet processing: " + packet, e);
					} else {
						//log.log(Level.FINEST, "[" + getName() + "] Stopping processing thread");
					}
				}    // end of try-catch
			}      // end of while (! threadStopped)
		}

		@Override
		public String toString() {
			return String.valueOf(packetCounter);
		}
	}
}    // AbstractMessageReceiver
