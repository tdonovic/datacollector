/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.rabbitmq;

import com.google.common.base.Optional;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.OffsetCommitter;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.lib.parser.DataParser;
import com.streamsets.pipeline.lib.parser.DataParserException;
import com.streamsets.pipeline.lib.parser.DataParserFactory;
import com.streamsets.pipeline.lib.rabbitmq.config.Errors;
import com.streamsets.pipeline.lib.rabbitmq.config.Groups;
import com.streamsets.pipeline.lib.rabbitmq.common.RabbitCxnManager;
import com.streamsets.pipeline.lib.rabbitmq.common.RabbitUtil;
import com.streamsets.pipeline.stage.origin.lib.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.origin.lib.ErrorRecordHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TransferQueue;

public class RabbitSource extends BaseSource implements OffsetCommitter {
  private static final Logger LOG = LoggerFactory.getLogger(RabbitSource.class);

  private final RabbitSourceConfigBean conf;
  private final TransferQueue<RabbitMessage> messages = new LinkedTransferQueue<>();

  private ErrorRecordHandler errorRecordHandler;
  private RabbitCxnManager rabbitCxnManager = new RabbitCxnManager();
  private StreamSetsMessageConsumer consumer;
  private DataParserFactory parserFactory;
  private String lastSourceOffset;

  public RabbitSource(RabbitSourceConfigBean conf) {
    this.conf = conf;
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();

    this.lastSourceOffset = "";

    RabbitUtil.initRabbitStage(
        getContext(),
        conf,
        conf.dataFormat,
        conf.dataFormatConfig,
        rabbitCxnManager,
        issues
    );

    if (!issues.isEmpty()) {
      return issues;
    }

    try {
      startConsuming();
      errorRecordHandler = new DefaultErrorRecordHandler(getContext());
      parserFactory = conf.dataFormatConfig.getParserFactory();
    } catch (IOException e) {
      // Some other issue.
      LOG.error("Rabbit MQ issue.", e);

      String reason = (e.getCause() == null) ? e.toString() : e.getCause().toString();

      issues.add(getContext().createConfigIssue(
          Groups.RABBITMQ.name(),
          "conf.uri",
          Errors.RABBITMQ_01,
          reason
      ));
    }

    return issues;
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    if (!isConnected() && !conf.advanced.automaticRecoveryEnabled) {
      // If we don't have automatic recovery enabled and the connection is closed, we should stop the pipeline.
      throw new StageException(Errors.RABBITMQ_05);
    }

    long maxTime = System.currentTimeMillis() + conf.basicConfig.maxWaitTime;
    int maxRecords = Math.min(maxBatchSize, conf.basicConfig.maxBatchSize);
    int numRecords = 0;
    String nextSourceOffset = lastSourceOffset;

    while (System.currentTimeMillis() < maxTime && numRecords < maxRecords) {
      try {
        RabbitMessage message = messages.poll(conf.basicConfig.maxWaitTime, TimeUnit.MILLISECONDS);
        if (message == null) {
          continue;
        }
        String recordId = message.getEnvelope().toString();
        Optional<Record> optional = parseRecord(recordId, message.getBody());
        if (optional.isPresent()) {
          Record record = optional.get();
          record.getHeader().setAttribute("deliveryTag", Long.toString(message.getEnvelope().getDeliveryTag()));
          batchMaker.addRecord(record);
          nextSourceOffset = record.getHeader().getAttribute("deliveryTag");
        }
        numRecords++;
      } catch (InterruptedException e) {
        LOG.warn("Pipeline is shutting down.");
      }
    }
    return nextSourceOffset;
  }

  @Override
  public void commit(String offset) throws StageException {
    if (offset == null || offset.isEmpty() || lastSourceOffset.equals(offset)) {
      return;
    }

    try {
      consumer.getChannel().basicAck(Long.parseLong(offset), true);
      lastSourceOffset = offset;
    } catch (IOException e) {
      LOG.error("Failed to acknowledge offset: {}", offset, e);
      throw new StageException(Errors.RABBITMQ_02, offset, e.toString());
    }
  }

  @Override
  public void destroy() {
    try {
      this.rabbitCxnManager.close();
    } catch (IOException | TimeoutException e) {
      LOG.warn("Error while closing channel/connection: {}", e.toString(), e);
    }
    super.destroy();
  }

  private void startConsuming() throws IOException {
    consumer = new StreamSetsMessageConsumer(this.rabbitCxnManager.getChannel(), messages);
    if (conf.consumerTag == null || conf.consumerTag.isEmpty()) {
      this.rabbitCxnManager.getChannel().basicConsume(conf.queue.name, false, consumer);
    } else {
      this.rabbitCxnManager.getChannel().basicConsume(conf.queue.name, false, conf.consumerTag, consumer);
    }
  }

  private Optional<Record> parseRecord(String id, byte[] data) throws StageException {
    Record record = null;
    try {
      DataParser parser = parserFactory.getParser(id, data);
      record = parser.parse();
    } catch (DataParserException | IOException e) {
      LOG.error("Failed to parse record from received message: '{}'", e.toString(), e);
      errorRecordHandler.onError(Errors.RABBITMQ_04, new String(data, parserFactory.getSettings().getCharset()));
    }
    return Optional.fromNullable(record);
  }

  private boolean isConnected() {
    return this.rabbitCxnManager.checkConnected();
  }
}
