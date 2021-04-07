package org.folio.verticle.consumers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.DataImportEventPayload;
import org.folio.dataimport.util.OkapiConnectionParams;
import org.folio.kafka.AsyncRecordHandler;
import org.folio.kafka.KafkaHeaderUtils;
import org.folio.processing.events.utils.ZIPArchiver;
import org.folio.rest.jaxrs.model.Event;
import org.folio.services.journal.JournalService;
import org.folio.verticle.consumers.util.EventTypeHandlerSelector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Qualifier("DataImportJournalKafkaHandler")
public class DataImportJournalKafkaHandler implements AsyncRecordHandler<String, String> {
  private static final Logger LOGGER = LogManager.getLogger();

  private Vertx vertx;
  private JournalService journalService;
  private EventTypeHandlerSelector eventTypeHandlerSelector = new EventTypeHandlerSelector();

  public DataImportJournalKafkaHandler(@Autowired Vertx vertx,
                                       @Autowired @Qualifier("journalServiceProxy") JournalService journalService) {
    this.vertx = vertx;
    this.journalService = journalService;
  }

  @Override
  public Future<String> handle(KafkaConsumerRecord<String, String> record) {
    Promise<String> result = Promise.promise();
    List<KafkaHeader> kafkaHeaders = record.headers();
    OkapiConnectionParams okapiConnectionParams = new OkapiConnectionParams(KafkaHeaderUtils.kafkaHeadersToMap(kafkaHeaders), vertx);
    Event event = new JsonObject(record.value()).mapTo(Event.class);
    LOGGER.error("Event was received: {}", event.getEventType());
    LOGGER.debug("Event was received: {}", event.getEventType());
    try {
      DataImportEventPayload eventPayload = new ObjectMapper().readValue(ZIPArchiver.unzip(event.getEventPayload()), DataImportEventPayload.class);
      if (eventPayload.getEventType().equals("DI_INVENTORY_INSTANCE_UPDATED_READY_FOR_POST_PROCESSING")) {
        eventPayload.setEventType(event.getEventType());
      }
      eventTypeHandlerSelector.getHandler(eventPayload).handle(journalService, eventPayload, okapiConnectionParams.getTenantId());
      result.complete();
    } catch (Exception e) {
      LOGGER.error("Error during processing journal event", e);
      result.fail(e);
    }
    return result.future();
  }

}
