/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include <pulsar/c/consumer.h>

#include "c_structs.h"

const char *pulsar_consumer_get_topic(pulsar_consumer_t *consumer) {
    return consumer->consumer.getTopic().c_str();
}

const char *pulsar_consumer_get_subscription_name(pulsar_consumer_t *consumer) {
    return consumer->consumer.getSubscriptionName().c_str();
}

pulsar_result pulsar_consumer_unsubscribe(pulsar_consumer_t *consumer) {
    return (pulsar_result)consumer->consumer.unsubscribe();
}

void pulsar_consumer_unsubscribe_async(pulsar_consumer_t *consumer, pulsar_result_callback callback) {
    consumer->consumer.unsubscribeAsync(boost::bind(handle_result_callback, _1, callback));
}

pulsar_result pulsar_consumer_receive(pulsar_consumer_t *consumer, pulsar_message_t **msg) {
    pulsar::Message message;
    pulsar::Result res = consumer->consumer.receive(message);
    if (res == pulsar::ResultOk) {
        (*msg) = new pulsar_message_t;
        (*msg)->message = message;
    }
    return (pulsar_result)res;
}

pulsar_result pulsar_consumer_receive_with_timeout(pulsar_consumer_t *consumer, pulsar_message_t **msg,
                                                   int timeoutMs) {
    pulsar::Message message;
    pulsar::Result res = consumer->consumer.receive(message, timeoutMs);
    if (res == pulsar::ResultOk) {
        (*msg) = new pulsar_message_t;
        (*msg)->message = message;
    }
    return (pulsar_result)res;
}

pulsar_result pulsar_consumer_acknowledge(pulsar_consumer_t *consumer, pulsar_message_t *message) {
    return (pulsar_result)consumer->consumer.acknowledge(message->message);
}

pulsar_result pulsar_consumer_acknowledge_id(pulsar_consumer_t *consumer, pulsar_message_id_t *messageId) {
    return (pulsar_result)consumer->consumer.acknowledge(messageId->messageId);
}

void pulsar_consumer_acknowledge_async(pulsar_consumer_t *consumer, pulsar_message_t *message,
                                       pulsar_result_callback callback) {
    consumer->consumer.acknowledgeAsync(message->message, boost::bind(handle_result_callback, _1, callback));
}

void pulsar_consumer_acknowledge_async_id(pulsar_consumer_t *consumer, pulsar_message_id_t *messageId,
                                          pulsar_result_callback callback) {
    consumer->consumer.acknowledgeAsync(messageId->messageId,
                                        boost::bind(handle_result_callback, _1, callback));
}

pulsar_result pulsar_consumer_acknowledge_cumulative(pulsar_consumer_t *consumer, pulsar_message_t *message) {
    return (pulsar_result)consumer->consumer.acknowledgeCumulative(message->message);
}

pulsar_result pulsar_consumer_acknowledge_cumulative_id(pulsar_consumer_t *consumer,
                                                        pulsar_message_id_t *messageId) {
    return (pulsar_result)consumer->consumer.acknowledge(messageId->messageId);
}

void pulsar_consumer_acknowledge_cumulative_async(pulsar_consumer_t *consumer, pulsar_message_t *message,
                                                  pulsar_result_callback callback) {
    consumer->consumer.acknowledgeCumulativeAsync(message->message,
                                                  boost::bind(handle_result_callback, _1, callback));
}

void pulsar_consumer_acknowledge_cumulative_async_id(pulsar_consumer_t *consumer,
                                                     pulsar_message_id_t *messageId,
                                                     pulsar_result_callback callback) {
    consumer->consumer.acknowledgeCumulativeAsync(messageId->messageId,
                                                  boost::bind(handle_result_callback, _1, callback));
}

pulsar_result pulsar_consumer_close(pulsar_consumer_t *consumer) {
    return (pulsar_result)consumer->consumer.close();
}

void pulsar_consumer_close_async(pulsar_consumer_t *consumer, pulsar_result_callback callback) {
    consumer->consumer.closeAsync(boost::bind(handle_result_callback, _1, callback));
}

void pulsar_consumer_free(pulsar_consumer_t *consumer) { delete consumer; }

pulsar_result pulsar_consumer_pause_message_listener(pulsar_consumer_t *consumer) {
    return (pulsar_result)consumer->consumer.pauseMessageListener();
}

pulsar_result resume_message_listener(pulsar_consumer_t *consumer) {
    return (pulsar_result)consumer->consumer.resumeMessageListener();
}

void redeliverUnacknowledgedMessages(pulsar_consumer_t *consumer) {
    return consumer->consumer.redeliverUnacknowledgedMessages();
}
