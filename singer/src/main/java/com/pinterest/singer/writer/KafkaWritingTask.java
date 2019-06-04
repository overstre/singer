/**
 * Copyright 2019 Pinterest, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.singer.writer;

import com.pinterest.singer.common.SingerMetrics;

import com.pinterest.singer.metrics.OpenTsdbMetricConverter;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class KafkaWritingTask implements Callable<KafkaWritingTaskResult> {

  private static final String UNKOWN_TOPIC = "unkown";
  private static final Logger LOG = LoggerFactory.getLogger(KafkaWritingTask.class);
  private KafkaProducer<byte[], byte[]> producer;
  private List<ProducerRecord<byte[], byte[]>> messages;
  private int writeTimeoutInSeconds;
  private long taskCreationTimeInMillis;

  public KafkaWritingTask(KafkaProducer<byte[], byte[]> producer,
                          List<ProducerRecord<byte[], byte[]>> msgs,
                          int writeTimeoutInSeconds) {
    this.producer = producer;
    this.messages = msgs;
    this.writeTimeoutInSeconds = writeTimeoutInSeconds;
    this.taskCreationTimeInMillis = System.currentTimeMillis();
  }

  @Override
  public KafkaWritingTaskResult call() {
    ArrayList<Future<RecordMetadata>> futures = new ArrayList<>();
    KafkaWritingTaskResult result = null;
    String topic = UNKOWN_TOPIC;

    try {
      if (messages.size() > 0) {
        topic = messages.get(0).topic();
        OpenTsdbMetricConverter.addMetric(SingerMetrics.WRITER_BATCH_SIZE,
            messages.size(), "topic=" + messages.get(0).topic(), "host=" + KafkaWriter.HOSTNAME);
      }
      for (ProducerRecord<byte[], byte[]> msg : messages) {
        Future<RecordMetadata> future = producer.send(msg);
        futures.add(future);
      }
      int bytesWritten = 0;
      for (Future<RecordMetadata> future : futures) {
        if (future.isCancelled()) {
          result = new KafkaWritingTaskResult(false, 0, 0);
          break;
        } else {
          // We will get TimeoutException if the wait timed out
          RecordMetadata recordMetadata = future.get(writeTimeoutInSeconds, TimeUnit.SECONDS);
          
          // used for tracking metrics
          if(recordMetadata != null) {
            bytesWritten += recordMetadata.serializedKeySize() + recordMetadata.serializedValueSize();
          }
        }
      }
      
      if (result == null) {
        long kafkaLatency = System.currentTimeMillis() - taskCreationTimeInMillis;
        // we shouldn't have latency creater than 2B milliseoncds so it should be okay
        // to downcast to integer
        result = new KafkaWritingTaskResult(true, bytesWritten, (int) kafkaLatency);
      }
    } catch (org.apache.kafka.common.errors.RecordTooLargeException e) {
      LOG.error("Kafka write failure due to excessively large message size", e);
      OpenTsdbMetricConverter.incr(SingerMetrics.OVERSIZED_MESSAGES, 1,
          "topic=" + topic, "host=" + KafkaWriter.HOSTNAME);
      result = new KafkaWritingTaskResult(false, e);
    } catch(org.apache.kafka.common.errors.SslAuthenticationException e) {
      LOG.error("Kafka write failure due to SSL authentication failure", e);
      OpenTsdbMetricConverter.incr(SingerMetrics.WRITER_SSL_EXCEPTION, 1, "topic=" + topic,
          "host=" + KafkaWriter.HOSTNAME);
      result = new KafkaWritingTaskResult(false, e);
    } catch (Exception e) {
      String errorMsg = "Failed to write " + messages.size() + " messages to kafka";
      LOG.error(errorMsg, e);
      OpenTsdbMetricConverter.incr(SingerMetrics.WRITE_FAILURE, 1, "topic=" + topic, "host=" + KafkaWriter.HOSTNAME);
      result = new KafkaWritingTaskResult(false, e);
    } finally {
      if (!result.success) {
        for (Future<RecordMetadata> future : futures) {
          if (!future.isCancelled() && !future.isDone()) {
            future.cancel(true);
          }
        }
      }
    }
    return result;
  }
}

