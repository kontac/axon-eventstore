package de.qyotta.neweventstore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.qyotta.eventstore.model.Entry;
import de.qyotta.eventstore.model.Event;
import de.qyotta.eventstore.model.EventResponse;
import de.qyotta.eventstore.utils.DefaultConnectionKeepAliveStrategy;
import io.prometheus.client.Histogram;
import io.prometheus.client.Histogram.Timer;

@SuppressWarnings("nls")
public final class ESHttpEventStore {
   private static final int DEFAULT_SOCKET_TIMEOUT = 60000;
   private static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT = 10000;
   private static final int DEFAULT_CONNECT_TIMEOUT = 10000;

   private static final int DEFAUT_LONG_POLL = 30;
   private static final String HOST_HEADER = "HOST";
   private static final Histogram SLICE_READ_HISTOGRAM = Histogram.build()
         .name("de_qyotta_http_reader_slice_read_time")
         .help("Read time per event slice")
         .labelNames("identifier", "hostAndPort")
         .buckets(0.01, 0.100, 1, 10, DEFAUT_LONG_POLL)
         .register();

   private static final Histogram EVENT_READ_HISTOGRAM = Histogram.build()
         .name("de_qyotta_http_reader_event_request_time")
         .help("Time for a single event request")
         .labelNames("identifier", "hostAndPort")
         .buckets(0.01, 0.100, 1, 10)
         .register();

   private static final Logger LOG = LoggerFactory.getLogger(ESHttpEventStore.class);

   private final ThreadFactory threadFactory;

   private final URL url;

   private final CredentialsProvider credentialsProvider;

   private CloseableHttpAsyncClient httpclient;

   private boolean open;

   private final AtomFeedJsonReader atomFeedReader;

   private final int longPollSec;
   private final String identifier;
   private final String hostAndPort;
   private String host;

   private int count;
   private final int connectTimeout;
   private final int connectionRequestTimeout;
   private final int socketTimeout;

   public ESHttpEventStore(final URL url, final CredentialsProvider credentialsProvider) {
      this("", null, url, credentialsProvider, DEFAUT_LONG_POLL, DEFAULT_CONNECT_TIMEOUT, DEFAULT_CONNECTION_REQUEST_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);
   }

   public ESHttpEventStore(final String identifier, final URL url, final CredentialsProvider credentialsProvider) {
      this(identifier, null, url, credentialsProvider, DEFAUT_LONG_POLL, DEFAULT_CONNECT_TIMEOUT, DEFAULT_CONNECTION_REQUEST_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);
   }

   public ESHttpEventStore(final String identifier, final ThreadFactory threadFactory, final URL url, final CredentialsProvider credentialsProvider, final int longPollSec, final int connectTimeout,
         final int connectionRequestTimeout, final int socketTimeout) {
      super();
      this.identifier = identifier;
      this.threadFactory = threadFactory;
      this.url = url;
      this.credentialsProvider = credentialsProvider;
      this.longPollSec = longPollSec;
      this.connectTimeout = connectTimeout;
      this.connectionRequestTimeout = connectionRequestTimeout;
      this.socketTimeout = socketTimeout;
      this.open = false;
      this.hostAndPort = url.getHost() + ":" + url.getPort();
      this.host = url.getHost();
      atomFeedReader = new AtomFeedJsonReader();
   }

   public void setHost(final String host) {
      this.host = host;
   }

   private void open() {
      if (open) {
         // Ignore
         return;
      }

      final HttpAsyncClientBuilder builder = HttpAsyncClients.custom()
            .setMaxConnPerRoute(1000)
            .setMaxConnTotal(1000)
            .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
            .setThreadFactory(threadFactory);
      if (credentialsProvider != null) {
         builder.setDefaultCredentialsProvider(credentialsProvider);
      }

      httpclient = builder.build();
      httpclient.start();
      this.open = true;
   }

   public void close() {
      if (!open) {
         // Ignore
         return;
      }
      try {
         httpclient.close();
      } catch (final IOException ex) {
         throw new RuntimeException("Cannot close http client", ex);
      }
      this.open = false;
   }

   public EventResponse readEvent(final String streamName, final int eventNumber) throws ReadFailedException {
      ensureOpen();

      final String msg = "readEvent(" + streamName + ", " + eventNumber + ")";
      try {
         final URI uri = new URIBuilder(url.toURI()).setPath("/streams/" + streamName + "/" + eventNumber)
               .build();
         return readEvent(uri, "");
      } catch (final URISyntaxException ex) {
         throw new ReadFailedException(streamName, msg, ex);
      }
   }

   private void ensureOpen() {
      if (!open) {
         open();
      }
   }

   public EventResponse readLastEvent(final String streamName) throws ReadFailedException {
      ensureOpen();

      final String msg = "readLastEvent(" + streamName + ")";
      try {
         final URI uri = new URIBuilder(url.toURI()).setPath("/streams/" + streamName + "/head/backward/1")
               .build();
         final List<Entry> entries = readFeed(streamName, uri, msg, "");
         final Entry entry = entries.get(0);

         return enrich(readEvent(new URI(entry.getId()), ""), entry);

      } catch (final URISyntaxException ex) {
         throw new ReadFailedException(streamName, msg, ex);
      }
   }

   public StreamEventsSlice readEventsForward(final String streamName, final long start, final int pCount, final String traceString) throws ReadFailedException {
      this.count = pCount;
      ensureOpen();

      final String msg = "readEventsForward(" + streamName + ", " + start + ", " + count + ")";
      try {
         final URI uri = new URIBuilder(url.toURI()).setPath("/streams/" + streamName + "/" + start + "/forward/" + count)
               .build();

         final boolean reverseOrder = false;
         final boolean forward = true;

         final List<Entry> entries = readFeed(streamName, uri, msg, traceString);
         return readEvents(forward, start, entries, reverseOrder);
      } catch (final URISyntaxException ex) {
         throw new ReadFailedException(streamName, msg, ex);
      }
   }

   public StreamEventsSlice readEventsBackward(final String streamName, final StreamEventsSlice slice, final int pCount, final String traceString) throws ReadFailedException {
      this.count = pCount;
      if (slice == null) {
         return readEventsBackward(streamName, 0, traceString);
      }
      return readEventsBackward(streamName, slice.getNextEventNumber(), traceString);
   }

   private StreamEventsSlice readEventsBackward(final String streamName, final long start, final String traceString) throws ReadFailedException {
      ensureOpen();

      final String msg = "readEventsBackward(" + streamName + ", " + start + ", " + count + ")";
      try {
         final URI uri = new URIBuilder(url.toURI()).setPath("/streams/" + streamName + "/" + start + "/backward/" + count)
               .build();
         final boolean reverseOrder = true;
         final boolean forward = false;

         final List<Entry> entries = readFeed(streamName, uri, msg, traceString);
         return readEvents(forward, start, entries, reverseOrder);
      } catch (final URISyntaxException ex) {
         throw new ReadFailedException(streamName, msg, ex);
      }
   }

   private List<Entry> readFeed(final String streamName, final URI uri, final String msg, final String traceString) throws ReadFailedException {
      final Timer startTimer = SLICE_READ_HISTOGRAM.labels(identifier, hostAndPort)
            .startTimer();

      final HttpGet get = createHttpGet(uri);
      try {
         final Future<HttpResponse> future = httpclient.execute(get, null);
         final HttpResponse response = future.get();

         final StatusLine statusLine = response.getStatusLine();
         if (statusLine.getStatusCode() == 200) {
            HttpEntity entity = response.getEntity();

            final Header contentEncodingHeader = entity.getContentEncoding();
            if (contentEncodingHeader != null) {
               final HeaderElement[] encodings = contentEncodingHeader.getElements();
               for (final HeaderElement encoding : encodings) {
                  if (encoding.getName()
                        .equalsIgnoreCase("gzip")) {
                     entity = new GzipDecompressingEntity(entity);
                     break;
                  }
               }
            }

            try {
               final InputStream in = entity.getContent();
               try {
                  final List<Entry> entries = atomFeedReader.readAtomFeed(in);
                  LOG.info("[" + traceString + "] found " + entries.size() + " in feed for: " + uri.toString());
                  return entries;
               } finally {
                  in.close();
               }
            } finally {
               EntityUtils.consume(entity);
            }
         }
         if (statusLine.getStatusCode() == 404) {
            // 404 Not Found
            LOG.warn("[" + traceString + "] " + msg + " RESPONSE: {}", response);
            throw new StreamNotFoundException(streamName);
         }
         if (statusLine.getStatusCode() == 410) {
            // Stream was hard deleted
            LOG.warn("[" + traceString + "] " + msg + " RESPONSE: {}", response);
            throw new StreamDeletedException(streamName);
         }
         LOG.warn("[" + traceString + "] " + msg + " RESPONSE: {}", response);
         throw new UnknownServerResponseException(streamName, " [Status=" + statusLine + "]");
      } catch (final Exception e) {
         throw new ReadFailedException(streamName, msg, e);
      } finally {
         startTimer.observeDuration();
         get.reset();
      }
   }

   private StreamEventsSlice readEvents(final boolean forward, final long fromEventNumber, final List<Entry> entries, final boolean reverseOrder) throws ReadFailedException, URISyntaxException {
      final List<EventResponse> events = new ArrayList<>();
      if (reverseOrder) {
         for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            events.add(doit(entry));
         }
      } else {
         for (int i = entries.size() - 1; i >= 0; i--) {
            final Entry entry = entries.get(i);
            events.add(doit(entry));
         }
      }
      final long nextEventNumber;
      final boolean endOfStream;
      if (forward) {
         nextEventNumber = fromEventNumber + events.size();
         endOfStream = count > events.size();
      } else {
         if (fromEventNumber - count < 0) {
            nextEventNumber = 0;
         } else {
            nextEventNumber = fromEventNumber - count;
         }
         endOfStream = fromEventNumber - count < 0;
      }

      return StreamEventsSlice.builder()
            .fromEventNumber(fromEventNumber)
            .nextEventNumber(nextEventNumber)
            .events(events)
            .endOfStream(endOfStream)
            .build();
   }

   private EventResponse doit(final Entry entry) {
      return EventResponse.builder()
            .author(entry.getAuthor())
            .summary(entry.getSummary())
            .id(entry.getId())
            .title(entry.getTitle())
            .updated(entry.getUpdated())
            .content(Event.builder()
                  .author(entry.getAuthor())
                  .data(entry.getData())
                  .eventId(entry.getEventId())
                  .eventNumber(entry.getEventNumber())
                  .eventStreamId(entry.getStreamId())
                  .eventType(entry.getEventType())
                  .id(entry.getId())
                  .isLinkMetaData(entry.getIsLinkMetaData())
                  .metadata(entry.getMetaData())
                  .positionEventNumber(entry.getPositionEventNumber())
                  .positionStreamId(entry.getPositionStreamId())
                  .streamId(entry.getStreamId())
                  .summary(entry.getSummary())
                  .title(entry.getTitle())
                  .updated(entry.getUpdated())
                  .build())
            .build();
   }

   private EventResponse enrich(final EventResponse event, final Entry entry) {
      final Event content = event.getContent();
      content.setEventId(entry.getEventId());
      content.setEventType(entry.getEventType());
      content.setEventNumber(entry.getEventNumber());
      content.setStreamId(entry.getStreamId());
      content.setIsLinkMetaData(entry.getIsLinkMetaData());
      content.setPositionEventNumber(entry.getPositionEventNumber());
      content.setPositionStreamId(entry.getPositionStreamId());
      content.setTitle(entry.getTitle());
      content.setId(entry.getId());
      content.setUpdated(entry.getUpdated());
      content.setUpdated(entry.getUpdated());
      content.setAuthor(entry.getAuthor());
      content.setSummary(entry.getSummary());
      return event;
   }

   private EventResponse readEvent(final URI uri, final String traceString) throws ReadFailedException {

      final String streamName = streamName(uri);

      final String msg = "readEvent(" + uri + ")";

      final Timer startTimer = EVENT_READ_HISTOGRAM.labels(identifier, hostAndPort)
            .startTimer();

      final HttpGet get = createHttpGet(uri);
      try {
         final Future<HttpResponse> future = httpclient.execute(get, null);
         final HttpResponse response = future.get();
         final StatusLine statusLine = response.getStatusLine();
         if (statusLine.getStatusCode() == 200) {
            HttpEntity entity = response.getEntity();

            final Header contentEncodingHeader = entity.getContentEncoding();
            if (contentEncodingHeader != null) {
               final HeaderElement[] encodings = contentEncodingHeader.getElements();
               for (final HeaderElement encoding : encodings) {
                  if (encoding.getName()
                        .equalsIgnoreCase("gzip")) {
                     entity = new GzipDecompressingEntity(entity);
                     break;
                  }
               }
            }

            try {
               final InputStream in = entity.getContent();
               try {
                  final EventResponse eventResponse = atomFeedReader.readEvent(in);
                  LOG.info("[" + traceString + "] read event from " + uri + " with response " + eventResponse);
                  return eventResponse;
               } finally {
                  in.close();
               }
            } finally {
               EntityUtils.consume(entity);
            }
         }
         if (statusLine.getStatusCode() == 404) {
            // 404 Not Found
            LOG.warn("[" + traceString + "]" + msg + " RESPONSE: {}", response);
            final int eventNumber = eventNumber(uri);
            throw new EventNotFoundException(streamName, eventNumber);
         }
         throw new ReadFailedException(streamName, msg + " [Status=" + statusLine + "]");

      } catch (final Exception e) {
         throw new ReadFailedException(streamName, msg, e);
      } finally {
         startTimer.observeDuration();
         get.reset();
      }

   }

   private String streamName(final URI uri) {
      // http://127.0.0.1:2113/streams/append_diff_and_read_stream/2
      final String myurl = uri.toString();
      final int p1 = myurl.indexOf("/streams/");
      if (p1 == -1) {
         throw new IllegalStateException("Failed to extract '/streams/': " + uri);
      }
      final int p2 = myurl.lastIndexOf('/');
      if (p2 == -1) {
         throw new IllegalStateException("Failed to extract last '/': " + uri);
      }
      final String str = myurl.substring(p1 + 9, p2);
      return str;
   }

   private int eventNumber(final URI uri) {
      // http://127.0.0.1:2113/streams/append_diff_and_read_stream/2
      final String myurl = uri.toString();
      final int p = myurl.lastIndexOf('/');
      if (p == -1) {
         throw new IllegalStateException("Failed to extract event number: " + uri);
      }
      final String str = myurl.substring(p + 1);
      return Integer.valueOf(str);
   }

   private HttpGet createHttpGet(final URI uri) {
      final HttpGet request = new HttpGet(uri + "?embed=body");

      request.setConfig(RequestConfig.custom()
            .setConnectionRequestTimeout(connectionRequestTimeout)
            .setConnectTimeout(connectTimeout)
            .setSocketTimeout(socketTimeout)
            .build());
      request.setHeader("Accept-Encoding", "gzip");
      request.setHeader("Accept", "application/vnd.eventstore.atom+json");
      request.setHeader("ES-LongPoll", String.valueOf(longPollSec));
      request.setHeader(HOST_HEADER, host);
      return request;
   }
}
