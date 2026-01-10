package com.commandbus.pgmq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class QueueNamesTest {

    @Test
    void commandQueueShouldReturnCorrectFormat() {
        assertEquals("payments__commands", QueueNames.commandQueue("payments"));
        assertEquals("orders__commands", QueueNames.commandQueue("orders"));
        assertEquals("test__commands", QueueNames.commandQueue("test"));
    }

    @Test
    void replyQueueShouldReturnCorrectFormat() {
        assertEquals("payments__replies", QueueNames.replyQueue("payments"));
        assertEquals("orders__replies", QueueNames.replyQueue("orders"));
        assertEquals("test__replies", QueueNames.replyQueue("test"));
    }

    @Test
    void notifyChannelShouldReturnCorrectFormat() {
        assertEquals("pgmq_notify_payments__commands", QueueNames.notifyChannel("payments__commands"));
        assertEquals("pgmq_notify_my_queue", QueueNames.notifyChannel("my_queue"));
    }

    @ParameterizedTest
    @CsvSource({
        "payments__commands,payments",
        "orders__replies,orders",
        "test__commands,test",
        "domain_with_underscore__commands,domain_with_underscore"
    })
    void extractDomainShouldExtractCorrectly(String queueName, String expectedDomain) {
        assertEquals(expectedDomain, QueueNames.extractDomain(queueName));
    }

    @Test
    void extractDomainShouldReturnOriginalWhenNoSeparator() {
        assertEquals("queueWithoutSeparator", QueueNames.extractDomain("queueWithoutSeparator"));
    }

    @Test
    void archiveTableShouldReturnCorrectFormat() {
        assertEquals("pgmq.a_payments__commands", QueueNames.archiveTable("payments__commands"));
        assertEquals("pgmq.a_test__replies", QueueNames.archiveTable("test__replies"));
    }

    @Test
    void constantsShouldHaveExpectedValues() {
        assertEquals("commands", QueueNames.COMMANDS_SUFFIX);
        assertEquals("replies", QueueNames.REPLIES_SUFFIX);
        assertEquals("pgmq_notify", QueueNames.NOTIFY_PREFIX);
        assertEquals("__", QueueNames.SEPARATOR);
    }

    @Test
    void commandAndReplyQueuesShouldUseSameDomain() {
        String domain = "myservice";
        String commandQueue = QueueNames.commandQueue(domain);
        String replyQueue = QueueNames.replyQueue(domain);

        assertEquals(domain, QueueNames.extractDomain(commandQueue));
        assertEquals(domain, QueueNames.extractDomain(replyQueue));
    }
}
