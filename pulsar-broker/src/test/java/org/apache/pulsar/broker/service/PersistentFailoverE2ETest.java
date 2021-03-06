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
package org.apache.pulsar.broker.service;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.broker.service.persistent.PersistentDispatcherSingleActiveConsumer;
import org.apache.pulsar.broker.service.persistent.PersistentSubscription;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.ConsumerEventListener;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.client.impl.TopicMessageImpl;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.FutureUtil;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class PersistentFailoverE2ETest extends BrokerTestBase {

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        super.baseSetup();
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    private static final int CONSUMER_ADD_OR_REMOVE_WAIT_TIME = 2000;

    private static class TestConsumerStateEventListener implements ConsumerEventListener {

        final LinkedBlockingQueue<Integer> activeQueue = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Integer> inActiveQueue = new LinkedBlockingQueue<>();

        @Override
        public void becameActive(Consumer<?> consumer, int partitionId) {
            try {
                activeQueue.put(partitionId);
            } catch (InterruptedException e) {
            }
        }

        @Override
        public void becameInactive(Consumer<?> consumer, int partitionId) {
            try {
                inActiveQueue.put(partitionId);
            } catch (InterruptedException e) {
            }
        }
    }

    private void verifyConsumerNotReceiveAnyStateChanges(TestConsumerStateEventListener listener) throws Exception {
        assertNull(listener.activeQueue.poll());
        assertNull(listener.inActiveQueue.poll());
    }

    private void verifyConsumerActive(TestConsumerStateEventListener listener, int partitionId) throws Exception {
        assertEquals(partitionId, listener.activeQueue.take().intValue());
        assertNull(listener.inActiveQueue.poll());
    }

    private void verifyConsumerInactive(TestConsumerStateEventListener listener, int partitionId) throws Exception {
        assertEquals(partitionId, listener.inActiveQueue.take().intValue());
        assertNull(listener.activeQueue.poll());
    }

    private static class ActiveInactiveListenerEvent implements ConsumerEventListener {

        private final Set<Integer> activePtns = Sets.newHashSet();
        private final Set<Integer> inactivePtns = Sets.newHashSet();

        @Override
        public synchronized void becameActive(Consumer<?> consumer, int partitionId) {
            activePtns.add(partitionId);
            inactivePtns.remove(partitionId);
        }

        @Override
        public synchronized void becameInactive(Consumer<?> consumer, int partitionId) {
            activePtns.remove(partitionId);
            inactivePtns.add(partitionId);
        }
    }

    @Test
    public void testSimpleConsumerEventsWithoutPartition() throws Exception {
        final String topicName = "persistent://prop/use/ns-abc/failover-topic1";
        final String subName = "sub1";
        final int numMsgs = 100;

        TestConsumerStateEventListener listener1 = new TestConsumerStateEventListener();
        TestConsumerStateEventListener listener2 = new TestConsumerStateEventListener();
        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer().topic(topicName).subscriptionName(subName)
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS).subscriptionType(SubscriptionType.Failover);


        // 1. two consumers on the same subscription
        ConsumerBuilder<byte[]> consumerBulder1 = consumerBuilder.clone().consumerName("1")
                .consumerEventListener(listener1).acknowledgmentGroupTime(0, TimeUnit.SECONDS);
        Consumer<byte[]> consumer1 = consumerBulder1.subscribe();
        Consumer<byte[]> consumer2 = consumerBuilder.clone().consumerName("2").consumerEventListener(listener2)
                .subscribe();
        verifyConsumerActive(listener1, -1);
        verifyConsumerInactive(listener2, -1);

        PersistentTopic topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(topicName).get();
        PersistentSubscription subRef = topicRef.getSubscription(subName);

        assertNotNull(topicRef);
        assertNotNull(subRef);

        // 2. validate basic dispatcher state
        assertTrue(subRef.getDispatcher().isConsumerConnected());
        assertEquals(subRef.getDispatcher().getType(), SubType.Failover);

        List<CompletableFuture<MessageId>> futures = Lists.newArrayListWithCapacity(numMsgs);
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        rolloverPerIntervalStats();

        assertEquals(subRef.getNumberOfEntriesInBacklog(), numMsgs);
        Thread.sleep(ASYNC_EVENT_COMPLETION_WAIT);

        // 3. consumer1 should have all the messages while consumer2 should have no messages
        Message<byte[]> msg = null;
        Assert.assertNull(consumer2.receive(1, TimeUnit.SECONDS));
        for (int i = 0; i < numMsgs; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer1.acknowledge(msg);
        }

        rolloverPerIntervalStats();

        // 4. messages deleted on individual acks
        Thread.sleep(ASYNC_EVENT_COMPLETION_WAIT);
        assertEquals(subRef.getNumberOfEntriesInBacklog(), 0);

        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        // 5. master consumer failure should resend unacked messages and new messages to another consumer
        for (int i = 0; i < 5; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer1.acknowledge(msg);
        }
        for (int i = 5; i < 10; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            // do not ack
        }
        consumer1.close();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);

        verifyConsumerActive(listener2, -1);
        verifyConsumerNotReceiveAnyStateChanges(listener1);
        for (int i = 5; i < numMsgs; i++) {
            msg = consumer2.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer2.acknowledge(msg);
        }
        Assert.assertNull(consumer2.receive(1, TimeUnit.SECONDS));

        rolloverPerIntervalStats();
        Thread.sleep(ASYNC_EVENT_COMPLETION_WAIT);
        assertEquals(subRef.getNumberOfEntriesInBacklog(), 0);

        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        // 6. consumer subscription should send messages to the new consumer if its name is highest in the list
        for (int i = 0; i < 5; i++) {
            msg = consumer2.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer2.acknowledge(msg);
        }
        consumer1 = consumerBulder1.subscribe();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);
        for (int i = 5; i < numMsgs; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer1.acknowledge(msg);
        }
        Assert.assertNull(consumer1.receive(1, TimeUnit.SECONDS));

        rolloverPerIntervalStats();
        Thread.sleep(ASYNC_EVENT_COMPLETION_WAIT);
        assertEquals(subRef.getNumberOfEntriesInBacklog(), 0);

        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        // 7. consumer subscription should not send messages to the new consumer if its name is not highest in the list
        for (int i = 0; i < 5; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer1.acknowledge(msg);
        }
        TestConsumerStateEventListener listener3 = new TestConsumerStateEventListener();
        Consumer<byte[]> consumer3 = consumerBuilder.clone().consumerName("3").consumerEventListener(listener3)
                .subscribe();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);

        verifyConsumerInactive(listener3, -1);

        Assert.assertNull(consumer3.receive(1, TimeUnit.SECONDS));
        for (int i = 5; i < numMsgs; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            Assert.assertEquals(new String(msg.getData()), "my-message-" + i);
            consumer1.acknowledge(msg);
        }

        rolloverPerIntervalStats();
        Thread.sleep(ASYNC_EVENT_COMPLETION_WAIT);
        assertEquals(subRef.getNumberOfEntriesInBacklog(), 0);

        // 8. unsubscribe not allowed if multiple consumers connected
        try {
            consumer1.unsubscribe();
            fail("should fail");
        } catch (PulsarClientException e) {
            // ok
        }

        // 9. unsubscribe allowed if there is a lone consumer
        consumer1.close();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);
        consumer2.close();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);
        try {
            consumer3.unsubscribe();
        } catch (PulsarClientException e) {
            fail("Should not fail", e);
        }

        Thread.sleep(ASYNC_EVENT_COMPLETION_WAIT);
        subRef = topicRef.getSubscription(subName);
        assertNull(subRef);

        producer.close();
        consumer3.close();

        admin.topics().delete(topicName);
    }

    @Test
    public void testSimpleConsumerEventsWithPartition() throws Exception {
        int numPartitions = 4;

        final String topicName = "persistent://prop/use/ns-abc/failover-topic2";
        final TopicName destName = TopicName.get(topicName);
        final String subName = "sub1";
        final int numMsgs = 100;
        Set<String> uniqueMessages = new HashSet<>();
        admin.topics().createPartitionedTopic(topicName, numPartitions);

        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer().topic(topicName).subscriptionName(subName)
                .subscriptionType(SubscriptionType.Failover);

        // 1. two consumers on the same subscription
        ActiveInactiveListenerEvent listener1 = new ActiveInactiveListenerEvent();
        ActiveInactiveListenerEvent listener2 = new ActiveInactiveListenerEvent();

        Consumer<byte[]> consumer1 = consumerBuilder.clone().consumerName("1").consumerEventListener(listener1)
                .subscribe();
        Consumer<byte[]> consumer2 = consumerBuilder.clone().consumerName("2").consumerEventListener(listener2)
                .subscribe();

        PersistentTopic topicRef;
        topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(destName.getPartition(0).toString()).get();
        PersistentDispatcherSingleActiveConsumer disp0 = (PersistentDispatcherSingleActiveConsumer) topicRef
                .getSubscription(subName).getDispatcher();
        topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(destName.getPartition(1).toString()).get();
        PersistentDispatcherSingleActiveConsumer disp1 = (PersistentDispatcherSingleActiveConsumer) topicRef
                .getSubscription(subName).getDispatcher();
        topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(destName.getPartition(2).toString()).get();
        PersistentDispatcherSingleActiveConsumer disp2 = (PersistentDispatcherSingleActiveConsumer) topicRef
                .getSubscription(subName).getDispatcher();
        topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(destName.getPartition(3).toString()).get();
        PersistentDispatcherSingleActiveConsumer disp3 = (PersistentDispatcherSingleActiveConsumer) topicRef
                .getSubscription(subName).getDispatcher();

        List<CompletableFuture<MessageId>> futures = Lists.newArrayListWithCapacity(numMsgs);
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.RoundRobinPartition).create();
        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        // equal distribution between both consumers
        int totalMessages = 0;
        Message<byte[]> msg = null;
        Set<Integer> receivedPtns = Sets.newHashSet();
        while (true) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                break;
            }
            totalMessages++;
            consumer1.acknowledge(msg);
            MessageIdImpl msgId = (MessageIdImpl) (((TopicMessageImpl)msg).getInnerMessageId());
            receivedPtns.add(msgId.getPartitionIndex());
        }

        assertTrue(Sets.difference(listener1.activePtns, receivedPtns).isEmpty());
        assertTrue(Sets.difference(listener2.inactivePtns, receivedPtns).isEmpty());

        Assert.assertEquals(totalMessages, numMsgs / 2);

        receivedPtns = Sets.newHashSet();
        while (true) {
            msg = consumer2.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                break;
            }
            totalMessages++;
            consumer2.acknowledge(msg);
            MessageIdImpl msgId = (MessageIdImpl) (((TopicMessageImpl)msg).getInnerMessageId());
            receivedPtns.add(msgId.getPartitionIndex());
        }
        assertTrue(Sets.difference(listener1.inactivePtns, receivedPtns).isEmpty());
        assertTrue(Sets.difference(listener2.activePtns, receivedPtns).isEmpty());

        Assert.assertEquals(totalMessages, numMsgs);
        Assert.assertEquals(disp0.getActiveConsumer().consumerName(), "1");
        Assert.assertEquals(disp1.getActiveConsumer().consumerName(), "2");
        Assert.assertEquals(disp2.getActiveConsumer().consumerName(), "1");
        Assert.assertEquals(disp3.getActiveConsumer().consumerName(), "2");
        totalMessages = 0;

        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        // add a consumer
        for (int i = 0; i < 20; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            uniqueMessages.add(new String(msg.getData()));
            consumer1.acknowledge(msg);
        }
        Consumer<byte[]> consumer3 = consumerBuilder.clone().consumerName("3").subscribe();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);
        int consumer1Messages = 0;
        while (true) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                Assert.assertEquals(consumer1Messages, 55);
                break;
            }
            consumer1Messages++;
            uniqueMessages.add(new String(msg.getData()));
            consumer1.acknowledge(msg);
        }
        int consumer2Messages = 0;
        while (true) {
            msg = consumer2.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                Assert.assertEquals(consumer2Messages, 50);
                break;
            }
            consumer2Messages++;
            uniqueMessages.add(new String(msg.getData()));
            consumer2.acknowledge(msg);
        }
        int consumer3Messages = 0;
        while (true) {
            msg = consumer3.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                Assert.assertEquals(consumer3Messages, 15, 10);
                break;
            }
            consumer3Messages++;
            uniqueMessages.add(new String(msg.getData()));
            consumer3.acknowledge(msg);
        }

        Assert.assertEquals(uniqueMessages.size(), numMsgs);
        Assert.assertEquals(disp0.getActiveConsumer().consumerName(), "1");
        Assert.assertEquals(disp1.getActiveConsumer().consumerName(), "2");
        Assert.assertEquals(disp2.getActiveConsumer().consumerName(), "3");
        Assert.assertEquals(disp3.getActiveConsumer().consumerName(), "1");
        uniqueMessages.clear();

        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();

        // remove a consumer
        for (int i = 0; i < 10; i++) {
            msg = consumer1.receive(1, TimeUnit.SECONDS);
            Assert.assertNotNull(msg);
            uniqueMessages.add(new String(msg.getData()));
            consumer1.acknowledge(msg);
        }
        consumer1.close();
        Thread.sleep(CONSUMER_ADD_OR_REMOVE_WAIT_TIME);
        consumer2Messages = 0;
        while (true) {
            msg = consumer2.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                Assert.assertEquals(consumer2Messages, 70, 5);
                break;
            }
            consumer2Messages++;
            uniqueMessages.add(new String(msg.getData()));
            consumer2.acknowledge(msg);
        }
        consumer3Messages = 0;
        while (true) {
            msg = consumer3.receive(1, TimeUnit.SECONDS);
            if (msg == null) {
                Assert.assertEquals(consumer3Messages, 70, 5);
                break;
            }
            consumer3Messages++;
            uniqueMessages.add(new String(msg.getData()));
            consumer3.acknowledge(msg);
        }

        Assert.assertEquals(uniqueMessages.size(), numMsgs);
        Assert.assertEquals(disp0.getActiveConsumer().consumerName(), "2");
        Assert.assertEquals(disp1.getActiveConsumer().consumerName(), "3");
        Assert.assertEquals(disp2.getActiveConsumer().consumerName(), "2");
        Assert.assertEquals(disp3.getActiveConsumer().consumerName(), "3");

        producer.close();
        consumer2.close();
        consumer3.unsubscribe();

        admin.topics().deletePartitionedTopic(topicName);
    }

    @Test
    public void testActiveConsumerFailoverWithDelay() throws Exception {
        final String topicName = "persistent://prop/use/ns-abc/failover-topic3";
        final String subName = "sub1";
        final int numMsgs = 100;
        List<Message<byte[]>> receivedMessages = Lists.newArrayList();

        ConsumerBuilder<byte[]> consumerBuilder = pulsarClient.newConsumer().topic(topicName).subscriptionName(subName)
                .subscriptionType(SubscriptionType.Failover).messageListener((consumer, msg) -> {
                    try {
                        synchronized (receivedMessages) {
                            receivedMessages.add(msg);
                        }
                        consumer.acknowledge(msg);
                    } catch (Exception e) {
                        fail("Should not fail");
                    }
                });

        ConsumerBuilder<byte[]> consumerBuilder1 = consumerBuilder.clone().consumerName("1");
        ConsumerBuilder<byte[]> consumerBuilder2 = consumerBuilder.clone().consumerName("2");

        conf.setActiveConsumerFailoverDelayTimeMillis(500);
        restartBroker();

        // create subscription
        Consumer<byte[]> consumer = consumerBuilder1.subscribe();
        consumer.close();
        PersistentTopic topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(topicName).get();
        PersistentSubscription subRef = topicRef.getSubscription(subName);

        // enqueue messages
        List<CompletableFuture<MessageId>> futures = Lists.newArrayListWithCapacity(numMsgs);
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        for (int i = 0; i < numMsgs; i++) {
            String message = "my-message-" + i;
            futures.add(producer.sendAsync(message.getBytes()));
        }
        FutureUtil.waitForAll(futures).get();
        futures.clear();
        producer.close();

        // two consumers subscribe at almost the same time
        CompletableFuture<Consumer<byte[]>> subscribeFuture2 = consumerBuilder2.subscribeAsync();
        CompletableFuture<Consumer<byte[]>> subscribeFuture1 = consumerBuilder1.subscribeAsync();

        // wait for all messages to be dequeued
        int retry = 20;
        for (int i = 0; i < retry; i++) {
            if (receivedMessages.size() >= numMsgs && subRef.getNumberOfEntriesInBacklog() == 0) {
                break;
            } else if (i != retry - 1) {
                Thread.sleep(100);
            }
        }

        // check if message duplication has occurred
        assertEquals(receivedMessages.size(), numMsgs);
        assertEquals(subRef.getNumberOfEntriesInBacklog(), 0);
        for (int i = 0; i < receivedMessages.size(); i++) {
            Assert.assertNotNull(receivedMessages.get(i));
            Assert.assertEquals(new String(receivedMessages.get(i).getData()), "my-message-" + i);
        }

        subscribeFuture1.get().close();
        subscribeFuture2.get().unsubscribe();
        admin.topics().delete(topicName);
        resetConfig();
        restartBroker();
    }
}
