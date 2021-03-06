package de.qyotta.eventstore;

import static com.jayway.restassured.RestAssured.given;
import static de.qyotta.eventstore.utils.Constants.ES_HARD_DELETE_HEADER;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.jayway.restassured.RestAssured;

import de.qyotta.eventstore.communication.EsContextDefaultImpl;
import de.qyotta.eventstore.model.Event;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@SuppressWarnings("nls")
public class AbstractEsTest {

   private static final InMemoryEventstoreProvider EVENT_STORE_PROVIDER = new InMemoryEventstoreProvider();
   private static final Logger LOGGER = LoggerFactory.getLogger(AbstractEsTest.class.getName());
   private static final int PORT = 4445;
   private static final String BASE_URL = "http://127.0.0.1";
   protected static final String HOST = BASE_URL + ":" + PORT;
   private static final String STREAMS = "/streams";
   protected static String BASE_STREAMS_URL = HOST + STREAMS + "/";
   protected Map<String, Event> expectedEvents;
   protected String streamName;
   protected EventStoreClient client;
   protected String streamUrl;

   protected static final Map<String, String> METADATA = new LinkedHashMap<>();
   static {
      METADATA.put("TEST", "TEST");
   }

   @BeforeClass
   public static void beforeClass() {
      RestAssured.baseURI = BASE_URL;
      RestAssured.port = PORT;
      RestAssured.basePath = STREAMS;
      if (!EVENT_STORE_PROVIDER.isRunning()) {
         EVENT_STORE_PROVIDER.start();
      }
   }

   @AfterClass
   public static void afterClass() {
      EVENT_STORE_PROVIDER.stop();
   }

   @Before
   public final void setupTest() {
      client = new EventStoreClient(new EsContextDefaultImpl(EventStoreSettings.withDefaults()
            .host(HOST)
            .build()));

      streamName = getClass().getSimpleName() + "-" + UUID.randomUUID();
      streamUrl = BASE_STREAMS_URL + streamName;
      expectedEvents = new HashMap<>();
   }

   protected static void deleteStream(final String streamUrl) {
      final int statusCode = given().headers(ES_HARD_DELETE_HEADER, true)
            .when()
            .delete(streamUrl)
            .andReturn()
            .statusCode();
      LOGGER.warn("Deleting stream '" + streamUrl + "' returned status code: " + statusCode);
   }

   @Getter
   @ToString
   @EqualsAndHashCode
   @NoArgsConstructor(access = AccessLevel.PUBLIC)
   @AllArgsConstructor(access = AccessLevel.PUBLIC)
   public static final class MyEvent {
      private String value;
   }

   protected static String metaData() {
      return new Gson().toJson(METADATA);
   }

   protected void createEvents(int numberOfEvents) throws InterruptedException {
      for (int i = 0; i < numberOfEvents; i++) {
         final String eventUuid = UUID.randomUUID()
               .toString();
         final Event event = Event.builder()
               .eventId(eventUuid)
               .eventType("Testtype")
               .data(new Gson().toJson(new MyEvent(eventUuid)))
               .metadata(metaData())
               .build();
         expectedEvents.put(eventUuid, event);
         client.appendEvent(streamName, event);
      }
   }
}
