-- V002: PGMQ Queue Creation
-- Creates all PGMQ queues for command bus and E2E testing
--
-- Queues are organized by domain and purpose:
-- - test domain: Basic testing
-- - payments domain: Payment processing demo
-- - reports domain: Reporting demo
-- - e2e domain: E2E testing framework
-- - reporting domain: Process manager demo

-- ============================================================================
-- Test Domain Queues
-- ============================================================================

SELECT pgmq.create('test__commands');
SELECT pgmq.create('test__replies');

-- ============================================================================
-- Payments Domain Queues
-- ============================================================================

SELECT pgmq.create('payments__commands');
SELECT pgmq.create('payments__replies');
SELECT pgmq.create('payments__process_replies');

-- ============================================================================
-- Reports Domain Queues
-- ============================================================================

SELECT pgmq.create('reports__commands');
SELECT pgmq.create('reports__replies');

-- ============================================================================
-- E2E Domain Queues
-- ============================================================================

SELECT pgmq.create('e2e__commands');
SELECT pgmq.create('e2e__replies');

-- ============================================================================
-- Reporting Domain Queues (Process Manager)
-- ============================================================================

SELECT pgmq.create('reporting__commands');
SELECT pgmq.create('reporting__replies');
SELECT pgmq.create('reporting__process_replies');
