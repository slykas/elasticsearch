/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.searchablesnapshots.cache;

import org.apache.lucene.store.IndexInput;
import org.elasticsearch.Version;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.lucene.store.ESIndexInputTestCase;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot;
import org.elasticsearch.index.store.StoreFileMetaData;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.mockstore.BlobContainerWrapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import static org.elasticsearch.xpack.searchablesnapshots.cache.TestUtils.createCacheService;
import static org.elasticsearch.xpack.searchablesnapshots.cache.TestUtils.numberOfRanges;
import static org.elasticsearch.xpack.searchablesnapshots.cache.TestUtils.singleBlobContainer;
import static org.hamcrest.Matchers.equalTo;

public class CacheBufferedIndexInputTests extends ESIndexInputTestCase {

    public void testRandomReads() throws IOException {
        try (CacheService cacheService = createCacheService(random())) {
            cacheService.start();

            SnapshotId snapshotId = new SnapshotId("_name", "_uuid");
            IndexId indexId = new IndexId("_name", "_uuid");
            ShardId shardId = new ShardId("_name", "_uuid", 0);

            for (int i = 0; i < 5; i++) {
                final String fileName = randomAlphaOfLength(10);
                final byte[] input = randomUnicodeOfLength(randomIntBetween(1, 100_000)).getBytes(StandardCharsets.UTF_8);

                final String blobName = randomUnicodeOfLength(10);
                final StoreFileMetaData metaData = new StoreFileMetaData(fileName, input.length, "_na", Version.CURRENT.luceneVersion);
                final BlobStoreIndexShardSnapshot snapshot = new BlobStoreIndexShardSnapshot(snapshotId.getName(), 0L,
                    List.of(new BlobStoreIndexShardSnapshot.FileInfo(blobName, metaData, new ByteSizeValue(input.length))), 0L, 0L, 0, 0L);

                BlobContainer blobContainer = singleBlobContainer(blobName, input);
                if (input.length <= cacheService.getCacheSize()) {
                    blobContainer = new CountingBlobContainer(blobContainer, cacheService.getRangeSize());
                }

                final Path cacheDir = createTempDir();
                try (CacheDirectory cacheDirectory
                         = new CacheDirectory(snapshot, blobContainer, cacheService, cacheDir, snapshotId, indexId, shardId, () -> 0L)) {
                    try (IndexInput indexInput = cacheDirectory.openInput(fileName, newIOContext(random()))) {
                        assertEquals(input.length, indexInput.length());
                        assertEquals(0, indexInput.getFilePointer());
                        byte[] output = randomReadAndSlice(indexInput, input.length);
                        assertArrayEquals(input, output);
                    }
                }

                if (blobContainer instanceof CountingBlobContainer) {
                    long numberOfRanges = numberOfRanges(input.length, cacheService.getRangeSize());
                    assertThat("Expected " + numberOfRanges + " ranges fetched from the source",
                        ((CountingBlobContainer) blobContainer).totalOpens.sum(), equalTo(numberOfRanges));
                    assertThat("All bytes should have been read from source",
                        ((CountingBlobContainer) blobContainer).totalBytes.sum(), equalTo((long) input.length));
                }
            }
        }
    }


    /**
     * BlobContainer that counts the number of {@link java.io.InputStream} it opens, as well as the
     * total number of bytes read from them.
     */
    private static class CountingBlobContainer extends BlobContainerWrapper {

        private final LongAdder totalBytes = new LongAdder();
        private final LongAdder totalOpens = new LongAdder();

        private final int rangeSize;

        CountingBlobContainer(BlobContainer in, int rangeSize) {
            super(in);
            this.rangeSize = rangeSize;
        }

        @Override
        public InputStream readBlob(String blobName, long position, long length) throws IOException {
            return new CountingInputStream(this, super.readBlob(blobName, position, length), length, rangeSize);
        }

        @Override
        public InputStream readBlob(String name) {
            assert false : "this method should never be called";
            throw new UnsupportedOperationException();
        }
    }

    /**
     * InputStream that counts the number of bytes read from it, as well as the positions
     * where read operations start and finish.
     */
    private static class CountingInputStream extends FilterInputStream {

        private final CountingBlobContainer container;
        private final int rangeSize;
        private final long length;

        private long bytesRead = 0L;
        private long position = 0L;
        private long start = Long.MAX_VALUE;
        private long end = Long.MIN_VALUE;

        CountingInputStream(CountingBlobContainer container, InputStream input, long length, int rangeSize) {
            super(input);
            this.container = Objects.requireNonNull(container);
            this.rangeSize = rangeSize;
            this.length = length;
            this.container.totalOpens.increment();
        }

        @Override
        public int read() throws IOException {
            if (position < start) {
                start = position;
            }

            final int result = in.read();
            if (result == -1) {
                return result;
            }
            bytesRead += 1L;
            position += 1L;

            if (position > end) {
                end = position;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int offset, int len) throws IOException {
            if (position < start) {
                start = position;
            }

            final int result = in.read(b, offset, len);
            bytesRead += len;
            position += len;

            if (position > end) {
                end = position;
            }
            return result;
        }

        @Override
        public void close() throws IOException {
            in.close();
            if (start % rangeSize != 0) {
                throw new AssertionError("Read operation should start at the beginning of a range");
            }
            if (end % rangeSize != 0) {
                if (end != length) {
                    throw new AssertionError("Read operation should finish at the end of a range or the end of the file");
                }
            }
            if (length <= rangeSize) {
                if (bytesRead != length) {
                    throw new AssertionError("All [" + length + "] bytes should have been read, no more no less but got:" + bytesRead);
                }
            } else {
                if (bytesRead != rangeSize) {
                    if (end != length) {
                        throw new AssertionError("Expecting [" + rangeSize + "] bytes to be read but got:" + bytesRead);

                    }
                    final long remaining = length % rangeSize;
                    if (bytesRead != remaining) {
                        throw new AssertionError("Expecting [" + remaining + "] bytes to be read but got:" + bytesRead);
                    }
                }
            }
            this.container.totalBytes.add(bytesRead);
        }
    }

}