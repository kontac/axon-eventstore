package de.qyotta.eventstore.utils;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.qyotta.eventstore.EventStream;
import de.qyotta.eventstore.EventStreamImpl;
import de.qyotta.eventstore.communication.ESContext;
import de.qyotta.eventstore.model.EventResponse;
import de.qyotta.eventstore.model.EventStreamReaderException;

@SuppressWarnings("nls")
public class EventStreamReaderImpl implements EventStreamReader {
   private static final Logger LOGGER = LoggerFactory.getLogger(EventStreamReaderImpl.class.getName());

   private long catchUpTerminationPeriodMillis = 30000;
   private final int intervalMillis;

   private boolean paused = false;
   private boolean isCatchingUp = false;

   private final String streamurl;
   private final ESContext context;

   private ScheduledExecutorService scheduler;
   private Runnable currentTask;

   private final EventStreamReaderCallback callback;
   private EventStreamReaderErrorCallback errorCallback = (errorMessage, cause) -> {
      LOGGER.error(errorMessage, cause);
      throw new EventStreamReaderException(cause);
   };

   @FunctionalInterface
   public interface EventStreamReaderCallback {
      void readEvent(final EventResponse event);
   }

   @FunctionalInterface
   public interface EventStreamReaderErrorCallback {
      void onError(final String errorMessage, final Throwable cause);
   }

   /**
    * Creates a new {@link EventStreamReaderImpl} that reads the given stream in intervals. Catch up is scheduled at the given interval. If the given interval is 0 or negative it will not be scheduled
    * but can be invoked manually.
    *
    * @param streamurl
    * @param context
    * @param intervalMillis
    * @param callback
    */
   public EventStreamReaderImpl(final String streamurl, final ESContext context, final int intervalMillis, final EventStreamReaderCallback callback) {
      this.streamurl = streamurl;
      this.context = context;
      this.intervalMillis = intervalMillis;
      this.callback = callback;
   }

   public EventStreamReaderImpl(final String streamurl, final ESContext context, final int intervalMillis, final EventStreamReaderCallback callback,
         final EventStreamReaderErrorCallback errorCallback) {
      this.streamurl = streamurl;
      this.context = context;
      this.intervalMillis = intervalMillis;
      this.callback = callback;
      this.errorCallback = errorCallback;
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader#start(java.lang.String)
    */
   @Override
   public void start(final String title) {
      try {
         shutdownIfNeeded();
         final EventStream eventStream = new EventStreamImpl(streamurl, context);
         eventStream.setAfterTitle(title);
         start(eventStream);
      } catch (final Throwable t) {
         errorCallback.onError("Error initializog event stream.", t);
      }
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader#start()
    */
   @Override
   public void start() {
      try {
         shutdownIfNeeded();
         final EventStream eventStream = new EventStreamImpl(streamurl, context);
         start(eventStream);
      } catch (final Throwable t) {
         errorCallback.onError("Error initializog event stream.", t);
      }
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader#start(java.util.Date)
    */
   @Override
   public void start(final Date timestamp) {
      try {
         shutdownIfNeeded();
         final EventStream eventStream = new EventStreamImpl(streamurl, context);
         eventStream.setAfterTimestamp(timestamp);
         start(eventStream);
      } catch (final Throwable t) {
         errorCallback.onError("Error initializog event stream.", t);
      }
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader#catchUp()
    */
   @Override
   public void catchUp() {
      if (currentTask != null) {
         currentTask.run();
         return;
      }
      // if start was never called just start, wich is equivalent to catching
      // up from the beginning
      start();
   }

   private void catchUp(final EventStream eventStream) {
      try {
         if (isPaused()) {
            return;
         }
         if (isCatchingUp) {
            return;
         }
         isCatchingUp = true;
         eventStream.loadNext();
         while (eventStream.hasNext() && !isPaused()) {
            callback.readEvent(eventStream.next());
         }
      } catch (final Throwable t) {
         errorCallback.onError("Error catching up to event stream.", t);
      } finally {
         isCatchingUp = false;
      }
   }

   private void shutdownIfNeeded() throws InterruptedException {
      if (scheduler != null) {
         scheduler.shutdown();
         scheduler.awaitTermination(catchUpTerminationPeriodMillis, TimeUnit.MILLISECONDS);
      }
   }

   private void start(final EventStream eventStream) {
      paused = false;
      currentTask = () -> catchUp(eventStream);
      if (intervalMillis > 0) {
         scheduler = Executors.newScheduledThreadPool(1);
         scheduler.scheduleWithFixedDelay(currentTask, 0, intervalMillis, TimeUnit.MILLISECONDS);
      }
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader#isPaused()
    */
   @Override
   public boolean isPaused() {
      return paused;
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader#setPaused(boolean)
    */
   @Override
   public void setPaused(boolean paused) {
      this.paused = paused;
   }

   /*
    * (non-Javadoc)
    *
    * @see de.qyotta.eventstore.utils.EventStreamReader# setCatchUpTerminationPeriodMillis(long)
    */
   @Override
   public void setCatchUpTerminationPeriodMillis(long catchUpTerminationPeriodMillis) {
      this.catchUpTerminationPeriodMillis = catchUpTerminationPeriodMillis;
   }

}
