package de.qyotta.axonframework.eventstore;

import static de.qyotta.axonframework.eventstore.utils.EsjcEventstoreUtil.getStreamName;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.axonframework.domain.DomainEventMessage;
import org.axonframework.domain.DomainEventStream;
import org.axonframework.eventstore.EventStore;
import org.axonframework.eventstore.EventStreamNotFoundException;
import org.axonframework.eventstore.PartialStreamSupport;
import org.axonframework.serializer.Revision;

import com.github.msemys.esjc.EventData;
import com.github.msemys.esjc.ExpectedVersion;
import com.google.gson.Gson;

import de.qyotta.axonframework.eventstore.utils.Constants;
import de.qyotta.axonframework.eventstore.utils.EsjcEventstoreUtil;

@SuppressWarnings({ "rawtypes", "nls" })
public class EsjcEventStore implements EventStore, PartialStreamSupport {
   private static final String AGGREGATE_OF_TYPE_S_WITH_IDENTIFIER_S_CANNOT_BE_FOUND = "Aggregate of type [%s] with identifier [%s] cannot be found.";
   private final com.github.msemys.esjc.EventStore client;
   private final Gson gson = new Gson();
   private String prefix = "domain";

   public EsjcEventStore(final com.github.msemys.esjc.EventStore client) {
      this.client = client;
   }

   @Override
   public void appendEvents(final String type, final DomainEventStream eventStream) {
      final Map<Object, List<EventData>> identifierToEventStoreEvents = new HashMap<>();
      while (eventStream.hasNext()) {
         final DomainEventMessage message = eventStream.next();
         final Object identifier = message.getAggregateIdentifier();
         if (!identifierToEventStoreEvents.containsKey(identifier)) {
            identifierToEventStoreEvents.put(identifier, new LinkedList<EventData>());
         }
         identifierToEventStoreEvents.get(identifier)
               .add(toEvent(message));
      }
      for (final Entry<Object, List<EventData>> entry : identifierToEventStoreEvents.entrySet()) {
         final String streamName = getStreamName(type, entry.getKey(), prefix);
         final List<EventData> events = entry.getValue();
         client.appendToStream(streamName, ExpectedVersion.ANY, events);
      }
   }

   @Override
   public DomainEventStream readEvents(final String type, final Object identifier) {
      try {
         final EsjcEventStreamBackedDomainEventStream eventStream = new EsjcEventStreamBackedDomainEventStream(EsjcEventstoreUtil.getStreamName(type, identifier, prefix), client);
         if (!eventStream.hasNext()) {
            throw new EventStreamNotFoundException(type, identifier);
         }
         return eventStream;
      } catch (final EventStreamNotFoundException e) {
         throw new EventStreamNotFoundException(String.format(AGGREGATE_OF_TYPE_S_WITH_IDENTIFIER_S_CANNOT_BE_FOUND, type, identifier), e);
      }
   }

   @Override
   public DomainEventStream readEvents(String type, Object identifier, long firstSequenceNumber) {
      try {
         final EsjcEventStreamBackedDomainEventStream eventStream = new EsjcEventStreamBackedDomainEventStream(EsjcEventstoreUtil.getStreamName(type, identifier, prefix), client, firstSequenceNumber);
         if (!eventStream.hasNext()) {
            throw new EventStreamNotFoundException(type, identifier);
         }
         return eventStream;
      } catch (final EventStreamNotFoundException e) {
         throw new EventStreamNotFoundException(String.format(AGGREGATE_OF_TYPE_S_WITH_IDENTIFIER_S_CANNOT_BE_FOUND, type, identifier), e);
      }
   }

   @Override
   public DomainEventStream readEvents(String type, Object identifier, long firstSequenceNumber, long lastSequenceNumber) {
      try {
         final EsjcEventStreamBackedDomainEventStream eventStream = new EsjcEventStreamBackedDomainEventStream(EsjcEventstoreUtil.getStreamName(type, identifier, prefix), client, firstSequenceNumber,
               lastSequenceNumber);
         if (!eventStream.hasNext()) {
            throw new EventStreamNotFoundException(type, identifier);
         }
         return eventStream;
      } catch (final EventStreamNotFoundException e) {
         throw new EventStreamNotFoundException(String.format(AGGREGATE_OF_TYPE_S_WITH_IDENTIFIER_S_CANNOT_BE_FOUND, type, identifier), e);
      }
   }

   private EventData toEvent(final DomainEventMessage message) {
      final HashMap<String, Object> metaData = new HashMap<>();
      final HashMap<String, Object> eventMetaData = new HashMap<>();
      for (final Entry<String, Object> entry : message.getMetaData()
            .entrySet()) {
         eventMetaData.put(entry.getKey(), entry.getValue());
      }

      metaData.put(Constants.AGGREGATE_ID_KEY, message.getAggregateIdentifier());
      metaData.put(Constants.PAYLOAD_REVISION_KEY, getPayloadRevision(message.getPayloadType()));
      metaData.put(Constants.EVENT_METADATA_KEY, eventMetaData);

      return EventData.newBuilder()
            .eventId(UUID.fromString(message.getIdentifier())) // TODO check if it is save to assume that this can always be converted to a UUID
            .jsonData(serialize(message.getPayload()))
            .type(message.getPayloadType()
                  .getName())
            .metadata(serialize(metaData))
            .build();
   }

   private String getPayloadRevision(final Class<?> payloadType) {
      final Revision revision = payloadType.getDeclaredAnnotation(Revision.class);
      if (revision != null) {
         return revision.value();
      }
      return null;
   }

   private String serialize(final Object payload) {
      return gson.toJson(payload);
   }

   /**
    * Set the prefix to use for domain-event-streams. This defaults to 'domain'.
    *
    * @param prefix
    */
   public void setPrefix(final String prefix) {
      this.prefix = prefix;
   }

}
