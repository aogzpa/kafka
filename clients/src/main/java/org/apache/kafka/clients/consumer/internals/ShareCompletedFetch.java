/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.errors.CorruptRecordException;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.message.ShareFetchResponseData;
import org.apache.kafka.common.record.ControlRecordType;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.requests.ShareFetchRequest;
import org.apache.kafka.common.requests.ShareFetchResponse;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.CloseableIterator;
import org.apache.kafka.common.utils.LogContext;
import org.slf4j.Logger;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * {@link ShareCompletedFetch} represents a {@link RecordBatch batch} of {@link Record records}
 * that was returned from the broker via a {@link ShareFetchRequest}. It contains logic to maintain
 * state between calls to {@link #fetchRecords(Deserializers, int, boolean)}. Although it has
 * similarities with {@link CompletedFetch}, the details are quite different, such as not needing
 * to keep track of aborted transactions or the need to keep track of fetch position.
 */
public class ShareCompletedFetch {

    final TopicIdPartition partition;
    final ShareFetchResponseData.PartitionData partitionData;
    final IsolationLevel isolationLevel;
    final short requestVersion;

    private final Logger log;
    private final BufferSupplier decompressionBufferSupplier;
    private final Iterator<? extends RecordBatch> batches;
    private final Set<Long> abortedProducerIds;
    private final PriorityQueue<ShareFetchResponseData.AbortedTransaction> abortedTransactions;
    private RecordBatch currentBatch;
    private Record lastRecord;
    private CloseableIterator<Record> records;
    private KafkaException cachedBatchException = null;
    private KafkaException cachedRecordException = null;
    private boolean isConsumed = false;
    private boolean initialized = false;
    private final List<OffsetAndDeliveryCount> acquiredRecordList;
    private ListIterator<OffsetAndDeliveryCount> acquiredRecordIterator;

    ShareCompletedFetch(final LogContext logContext,
                        final BufferSupplier decompressionBufferSupplier,
                        final TopicIdPartition partition,
                        final ShareFetchResponseData.PartitionData partitionData,
                        final IsolationLevel isolationLevel,
                        final short requestVersion) {
        this.log = logContext.logger(org.apache.kafka.clients.consumer.internals.ShareCompletedFetch.class);
        this.decompressionBufferSupplier = decompressionBufferSupplier;
        this.partition = partition;
        this.partitionData = partitionData;
        this.isolationLevel = isolationLevel;
        this.requestVersion = requestVersion;
        this.batches = ShareFetchResponse.recordsOrFail(partitionData).batches().iterator();
        this.acquiredRecordList = buildAcquiredRecordList(partitionData.acquiredRecords());
        this.abortedProducerIds = new HashSet<>();
        this.abortedTransactions = abortedTransactions(partitionData);
    }

    private List<OffsetAndDeliveryCount> buildAcquiredRecordList(List<ShareFetchResponseData.AcquiredRecords> partitionAcquiredRecords) {
        List<OffsetAndDeliveryCount> acquiredRecordList = new LinkedList<>();
        partitionAcquiredRecords.forEach(acquiredRecords -> {
            for (long offset = acquiredRecords.baseOffset(); offset <= acquiredRecords.lastOffset(); offset++) {
                acquiredRecordList.add(new OffsetAndDeliveryCount(offset, acquiredRecords.deliveryCount()));
            }
        });
        return acquiredRecordList;
    }

    boolean isInitialized() {
        return initialized;
    }

    void setInitialized() {
        this.initialized = true;
    }

    public boolean isConsumed() {
        return isConsumed;
    }

    /**
     * Draining a {@link ShareCompletedFetch} will signal that the data has been consumed and the underlying resources
     * are closed. This is somewhat analogous to {@link Closeable#close() closing}, though no error will result if a
     * caller invokes {@link #fetchRecords(Deserializers, int, boolean)}; an empty {@link List list} will be
     * returned instead.
     */
    void drain() {
        if (!isConsumed) {
            maybeCloseRecordStream();
            cachedRecordException = null;
            cachedBatchException = null;
            this.isConsumed = true;
        }
    }

    /**
     * The {@link RecordBatch batch} of {@link Record records} is converted to a {@link List list} of
     * {@link ConsumerRecord consumer records} and returned. {@link BufferSupplier Decompression} and
     * {@link Deserializer deserialization} of the {@link Record record's} key and value are performed in
     * this step.
     *
     * @param deserializers {@link Deserializer}s to use to convert the raw bytes to the expected key and value types
     * @param maxRecords The number of records to return; the number returned may be {@code 0 <= maxRecords}
     * @param checkCrcs Whether to check the CRC of fetched records
     *
     * @return {@link ShareInFlightBatch The ShareInFlightBatch containing records and their acknowledgments}
     */
    <K, V> ShareInFlightBatch<K, V> fetchRecords(final Deserializers<K, V> deserializers,
                                                 final int maxRecords,
                                                 final boolean checkCrcs) {
        // Creating an empty ShareInFlightBatch
        ShareInFlightBatch<K, V> inFlightBatch = new ShareInFlightBatch<>(partition);

        if (cachedBatchException != null) {
            // If the event that a CRC check fails, reject the entire record batch because it is corrupt.
            rejectRecordBatch(inFlightBatch, currentBatch);
            inFlightBatch.setException(cachedBatchException);
            cachedBatchException = null;
            return inFlightBatch;
        }

        if (cachedRecordException != null) {
            inFlightBatch.addAcknowledgement(lastRecord.offset(), AcknowledgeType.RELEASE);
            inFlightBatch.setException(
                    new KafkaException("Received exception when fetching the next record from " + partition +
                            ". The record has been released.", cachedRecordException));
            cachedRecordException = null;
            return inFlightBatch;
        }

        if (isConsumed)
            return inFlightBatch;

        OffsetAndDeliveryCount nextAcquired = initializeAcquiredRecord();

        try {
            for (int i = 0; i < maxRecords; i++) {
                lastRecord = nextFetchedRecord(checkCrcs);
                if (lastRecord == null) {
                    // Any remaining acquired records are gaps
                    while (nextAcquired != null) {
                        inFlightBatch.addGap(nextAcquired.offset);
                        nextAcquired = nextAcquiredRecord();
                    }
                    break;
                }

                while (nextAcquired != null) {
                    if (lastRecord.offset() == nextAcquired.offset) {
                        // It's acquired, so we parse it and add it to the batch
                        Optional<Integer> leaderEpoch = maybeLeaderEpoch(currentBatch.partitionLeaderEpoch());
                        TimestampType timestampType = currentBatch.timestampType();
                        ConsumerRecord<K, V> record = parseRecord(deserializers, partition, leaderEpoch,
                                timestampType, lastRecord, nextAcquired.deliveryCount);
                        inFlightBatch.addRecord(record);

                        nextAcquired = nextAcquiredRecord();
                        break;
                    } else if (lastRecord.offset() < nextAcquired.offset) {
                        // It's not acquired, so we skip it
                        break;
                    } else {
                        // It's acquired, but there's no non-control record at this offset, so it's a gap
                        inFlightBatch.addGap(nextAcquired.offset);
                    }

                    nextAcquired = nextAcquiredRecord();
                }
            }
        } catch (SerializationException se) {
            nextAcquired = nextAcquiredRecord();
            if (inFlightBatch.isEmpty()) {
                inFlightBatch.addAcknowledgement(lastRecord.offset(), AcknowledgeType.RELEASE);
                inFlightBatch.setException(se);
            } else {
                cachedRecordException = se;
            }
        } catch (CorruptRecordException e) {
            if (inFlightBatch.isEmpty()) {
                // If the event that a CRC check fails, reject the entire record batch because it is corrupt.
                rejectRecordBatch(inFlightBatch, currentBatch);
                inFlightBatch.setException(e);
            } else {
                cachedBatchException = e;
            }
        }

        return inFlightBatch;
    }

    private OffsetAndDeliveryCount initializeAcquiredRecord() {
        if ((acquiredRecordIterator != null) && (acquiredRecordIterator.hasPrevious())) {
            acquiredRecordIterator.previous();
            return acquiredRecordIterator.next();
        } else {
            acquiredRecordIterator = acquiredRecordList.listIterator();
            if (acquiredRecordIterator.hasNext()) {
                return acquiredRecordIterator.next();
            }
            return null;
        }
    }

    private OffsetAndDeliveryCount nextAcquiredRecord() {
        if (acquiredRecordIterator.hasNext()) {
            return acquiredRecordIterator.next();
        }
        return null;
    }

    private <K, V> void rejectRecordBatch(final ShareInFlightBatch<K, V> inFlightBatch,
                                          final RecordBatch currentBatch) {
        // Rewind the acquiredRecordIterator to the start, so we are in a known state
        acquiredRecordIterator = acquiredRecordList.listIterator();

        OffsetAndDeliveryCount nextAcquired = nextAcquiredRecord();
        for (long offset = currentBatch.baseOffset(); offset <= currentBatch.lastOffset(); offset++) {
            if (nextAcquired == null) {
                // No more acquired records, so we are done
                break;
            } else if (offset == nextAcquired.offset) {
                // It's acquired, so we reject it
                inFlightBatch.addAcknowledgement(offset, AcknowledgeType.REJECT);
            } else if (offset < nextAcquired.offset) {
                // It's not acquired, so we skip it
                continue;
            }

            nextAcquired = nextAcquiredRecord();
        }
    }

    /**
     * Parse the record entry, deserializing the key / value fields if necessary
     */
    <K, V> ConsumerRecord<K, V> parseRecord(final Deserializers<K, V> deserializers,
                                            final TopicIdPartition partition,
                                            final Optional<Integer> leaderEpoch,
                                            final TimestampType timestampType,
                                            final Record record,
                                            final short deliveryCount) {
        try {
            long offset = record.offset();
            long timestamp = record.timestamp();
            Headers headers = new RecordHeaders(record.headers());
            ByteBuffer keyBytes = record.key();
            K key = keyBytes == null ? null : deserializers.keyDeserializer.deserialize(partition.topic(), headers, keyBytes);
            ByteBuffer valueBytes = record.value();
            V value = valueBytes == null ? null : deserializers.valueDeserializer.deserialize(partition.topic(), headers, valueBytes);
            return new ConsumerRecord<>(partition.topic(), partition.partition(), offset,
                    timestamp, timestampType,
                    keyBytes == null ? ConsumerRecord.NULL_SIZE : keyBytes.remaining(),
                    valueBytes == null ? ConsumerRecord.NULL_SIZE : valueBytes.remaining(),
                    key, value, headers, leaderEpoch, Optional.of(deliveryCount));
        } catch (RuntimeException e) {
            log.error("Deserializers with error: {}", deserializers);
            throw new RecordDeserializationException(partition.topicPartition(), record.offset(),
                    "Error deserializing key/value for partition " + partition +
                    " at offset " + record.offset() + ". The record has been released.", e);
        }
    }

    private Record nextFetchedRecord(final boolean checkCrcs) {
        while (true) {
            if (records == null || !records.hasNext()) {
                maybeCloseRecordStream();

                if (!batches.hasNext()) {
                    drain();
                    return null;
                }

                currentBatch = batches.next();
                maybeEnsureValid(currentBatch, checkCrcs);

                if (isolationLevel == IsolationLevel.READ_COMMITTED && currentBatch.hasProducerId()) {
                    consumeAbortedTransactionsUpTo(currentBatch.lastOffset());

                    long producerId = currentBatch.producerId();
                    if (containsAbortMarker(currentBatch)) {
                        abortedProducerIds.remove(producerId);
                    } else if (isBatchAborted(currentBatch)) {
                        log.debug("Skipping aborted record batch from partition {} with producerId {} and " +
                                "offsets {} to {}",
                                partition, producerId, currentBatch.baseOffset(), currentBatch.lastOffset());
                        continue;
                    }
                }

                records = currentBatch.streamingIterator(decompressionBufferSupplier);
            } else {
                Record record = records.next();
                maybeEnsureValid(record, checkCrcs);

                // control records are not returned to the user
                if (!currentBatch.isControlBatch()) {
                    return record;
                }
            }
        }
    }

    private Optional<Integer> maybeLeaderEpoch(final int leaderEpoch) {
        return leaderEpoch == RecordBatch.NO_PARTITION_LEADER_EPOCH ? Optional.empty() : Optional.of(leaderEpoch);
    }

    private void maybeEnsureValid(RecordBatch batch, boolean checkCrcs) {
        if (checkCrcs && batch.magic() >= RecordBatch.MAGIC_VALUE_V2) {
            try {
                batch.ensureValid();
            } catch (CorruptRecordException e) {
                throw new CorruptRecordException("Record batch for partition " + partition.topicPartition()
                        + " at offset " + batch.baseOffset() + " is invalid, cause: " + e.getMessage());
            }
        }
    }

    private void maybeEnsureValid(final Record record, final boolean checkCrcs) {
        if (checkCrcs) {
            try {
                record.ensureValid();
            } catch (CorruptRecordException e) {
                throw new CorruptRecordException("Record for partition " + partition.topicPartition()
                        + " at offset " + record.offset() + " is invalid, cause: " + e.getMessage());
            }
        }
    }

    private void maybeCloseRecordStream() {
        if (records != null) {
            records.close();
            records = null;
        }
    }

    private void consumeAbortedTransactionsUpTo(long offset) {
        if (abortedTransactions == null)
            return;

        while (!abortedTransactions.isEmpty() && abortedTransactions.peek().firstOffset() <= offset) {
            ShareFetchResponseData.AbortedTransaction abortedTransaction = abortedTransactions.poll();
            abortedProducerIds.add(abortedTransaction.producerId());
        }
    }

    private boolean isBatchAborted(RecordBatch batch) {
        return batch.isTransactional() && abortedProducerIds.contains(batch.producerId());
    }

    private PriorityQueue<ShareFetchResponseData.AbortedTransaction> abortedTransactions(ShareFetchResponseData.PartitionData partition) {
        if (partition.abortedTransactions() == null || partition.abortedTransactions().isEmpty())
            return null;

        PriorityQueue<ShareFetchResponseData.AbortedTransaction> abortedTransactions = new PriorityQueue<>(
                partition.abortedTransactions().size(), Comparator.comparingLong(ShareFetchResponseData.AbortedTransaction::firstOffset)
        );
        abortedTransactions.addAll(partition.abortedTransactions());
        return abortedTransactions;
    }

    private boolean containsAbortMarker(RecordBatch batch) {
        if (!batch.isControlBatch())
            return false;

        Iterator<Record> batchIterator = batch.iterator();
        if (!batchIterator.hasNext())
            return false;

        Record firstRecord = batchIterator.next();
        return ControlRecordType.ABORT == ControlRecordType.parse(firstRecord.key());
    }

    private static class OffsetAndDeliveryCount {
        final long offset;
        final short deliveryCount;

        OffsetAndDeliveryCount(long offset, short deliveryCount) {
            this.offset = offset;
            this.deliveryCount = deliveryCount;
        }

        @Override
        public String toString() {
            return "OffsetAndDeliveryCount{" +
                    "offset=" + offset +
                    ", deliveryCount=" + deliveryCount +
                    '}';
        }
    }
}
