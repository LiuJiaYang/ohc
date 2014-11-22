/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates access to hash entries.
 */
final class HashEntryAccess
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HashEntryAccess.class);

    // offset of next block address in a "next block"
    static final int OFF_NEXT_BLOCK = 0;
    // offset of serialized hash value
    static final int OFF_HASH = 8;
    // offset of previous hash entry in LRU list for this hash partition
    static final int OFF_LRU_PREVIOUS = 16;
    // offset of next hash entry in LRU list for this hash partition
    static final int OFF_LRU_NEXT = 24;
    // offset of serialized hash key length
    static final int OFF_HASH_KEY_LENGTH = 32;
    // offset of serialized value length
    static final int OFF_VALUE_LENGTH = 40;
    // offset of entry lock
    static final int OFF_ENTRY_LOCK = 48;
    // offset of data in first block
    static final int OFF_DATA_IN_FIRST = 64;

    // offset of data in "next block"
    static final int OFF_DATA_IN_NEXT = 8;

    private final int blockSize;
    private final int hashTableSize;

    private final Uns uns;
    private final FreeBlocks freeBlocks;
    private final HashPartitionAccess hashPartitionAccess;

    private final int firstBlockDataSpace;
    private final int nextBlockDataSpace;
    private final int lruIterationWarnTrigger;

    private long lastLruWarn;

    HashEntryAccess(Uns uns, int blockSize, FreeBlocks freeBlocks, HashPartitionAccess hashPartitionAccess, int hashTableSize, int lruIterationWarnTrigger)
    {
        this.uns = uns;
        this.blockSize = blockSize;
        this.freeBlocks = freeBlocks;
        this.hashPartitionAccess = hashPartitionAccess;
        this.hashTableSize = hashTableSize;
        this.lruIterationWarnTrigger = lruIterationWarnTrigger;

        firstBlockDataSpace = blockSize - OFF_DATA_IN_FIRST;
        nextBlockDataSpace = blockSize - OFF_DATA_IN_NEXT;
    }

    long createNewEntryChain(int hash, BytesSource keySource, BytesSource valueSource, long valueLen)
    {
        if (valueSource != null)
            valueLen = valueSource.size();
        if (valueLen < 0)
            throw new IllegalArgumentException();

        int requiredBlocks = calcRequiredNumberOfBlocks(keySource, valueLen);
        if (requiredBlocks < 1)
            throw new InternalError();

        // allocate memory for whole hash-entry block-chain
        long hashEntryAdr = freeBlocks.allocateChain(requiredBlocks);
        if (hashEntryAdr == 0L)
            return 0L;

        // initialize hash entry fields
        initHashEntry(hash, keySource, valueSource, valueLen, hashEntryAdr);

        // write key + value to data
        long blkAdr = hashEntryAdr;
        long blkOff = OFF_DATA_IN_FIRST;
        int blkRemain = firstBlockDataSpace;

        // write key to data
        long len = keySource.size();
        if (keySource.hasArray())
        {
            // keySource provides array access (can use Unsafe.copyMemory)
            byte[] arr = keySource.array();
            int arrOff = keySource.arrayOffset();
            int arrIdx = 0;
            while (arrIdx < len)
            {
                long remain = len - arrIdx;
                if (remain > blkRemain)
                {
                    uns.copyMemory(arr, arrIdx + arrOff, blkAdr + blkOff, blkRemain);
                    arrIdx += blkRemain;
                    blkAdr = getNextBlock(blkAdr);
                    blkOff = OFF_DATA_IN_NEXT;
                    blkRemain = nextBlockDataSpace;
                }
                else
                {
                    uns.copyMemory(arr, arrIdx + arrOff, blkAdr + blkOff, (int) remain);
                    blkOff += remain;
                    if (blkOff == blockSize)
                    {
                        blkAdr = getNextBlock(blkAdr);
                        blkOff = OFF_DATA_IN_NEXT;
                        blkRemain = nextBlockDataSpace;
                    }
                    else
                        blkRemain -= remain;
                    break;
                }
            }
        }
        else
        {
            // keySource has no array
            for (int p = 0; p < len; p++)
            {
                byte b = keySource.getByte(p);
                uns.putByte(blkAdr + blkOff++, b);
                if (blkOff == blockSize)
                {
                    blkAdr = getNextBlock(blkAdr);
                    blkOff = OFF_DATA_IN_NEXT;
                }
            }
        }

        // round up to next 8 byte boundary (8 byte copy operations on 8 byte boundaries are faster)
        long add = roundUpRel(blkOff);
        blkOff += add;
        blkRemain -= add;
        if (blkOff >= blockSize)
        {
            blkAdr = getNextBlock(blkAdr);
            blkOff = OFF_DATA_IN_NEXT;
        }

        if (valueSource != null)
        {
            // write value to data
            //
            // Although the following code is similar to the one used for the key above, it would require us to
            // allocate objects to be able to use the same code for both - so...
            //
            len = valueSource.size();
            if (valueSource.hasArray())
            {
                // valueSource provides array access (can use Unsafe.copyMemory)
                byte[] arr = valueSource.array();
                int arrOff = valueSource.arrayOffset();
                int arrIdx = 0;
                while (arrIdx < len)
                {
                    long remain = len - arrIdx;
                    if (remain > blkRemain)
                    {
                        uns.copyMemory(arr, arrIdx + arrOff, blkAdr + blkOff, blkRemain);
                        arrIdx += blkRemain;
                        blkAdr = getNextBlock(blkAdr);
                        blkOff = OFF_DATA_IN_NEXT;
                        blkRemain = nextBlockDataSpace;
                    }
                    else
                    {
                        uns.copyMemory(arr, arrIdx + arrOff, blkAdr + blkOff, (int) remain);
                        break;
                    }
                }
            }
            else
            {
                // valueSource has no array
                for (int p = 0; p < len; p++)
                {
                    byte b = valueSource.getByte(p);
                    uns.putByte(blkAdr + blkOff++, b);
                    if (blkOff == blockSize)
                    {
                        blkAdr = getNextBlock(blkAdr);
                        blkOff = OFF_DATA_IN_NEXT;
                    }
                }
            }
        }

        return hashEntryAdr;
    }

    private int calcRequiredNumberOfBlocks(BytesSource keySource, long valueLen)
    {
        long total = roundUp(keySource.size()) + valueLen;

        total -= firstBlockDataSpace;
        if (total <= 0L)
            return 1;

        int blk = (int) (1L + total / nextBlockDataSpace);

        return (total % nextBlockDataSpace) != 0
               ? blk + 1
               : blk;
    }

    private void initHashEntry(int hash, BytesSource keySource, BytesSource valueSource, long valueLen, long hashEntryAdr)
    {
        uns.putLongVolatile(hashEntryAdr + OFF_HASH, hash);
        setLRUPrevious(hashEntryAdr, 0L);
        setLRUNext(hashEntryAdr, 0L);
        uns.putLongVolatile(hashEntryAdr + OFF_ENTRY_LOCK, 0L);
        uns.putLongVolatile(hashEntryAdr + OFF_HASH_KEY_LENGTH, keySource.size());
        uns.putLongVolatile(hashEntryAdr + OFF_VALUE_LENGTH, valueSource != null ? valueSource.size() : valueLen);
    }

    long findHashEntry(long partitionAdr, int hash, BytesSource keySource)
    {
        if (partitionAdr == 0L)
            return 0L;

        long firstHashEntryAdr = hashPartitionAccess.getLRUHead(partitionAdr);
        boolean first = true;
        int loops = 0;
        for (long hashEntryAdr = firstHashEntryAdr; hashEntryAdr != 0L; hashEntryAdr = getLRUNext(hashEntryAdr), loops++)
        {
            if (!first && firstHashEntryAdr == hashEntryAdr)
                throw new InternalError("endless loop");
            first = false;

            long hashEntryHash = uns.getLongVolatile(hashEntryAdr + OFF_HASH);
            if (hashEntryHash != hash)
                continue;

            long serKeyLen = uns.getLongVolatile(hashEntryAdr + OFF_HASH_KEY_LENGTH);
            if (serKeyLen != keySource.size())
                continue;

            if (!compareKey(hashEntryAdr, keySource, serKeyLen))
                continue;

            if (loops >= lruIterationWarnTrigger)
                lruListLongWarn(loops);

            return hashEntryAdr;
        }

        if (loops >= lruIterationWarnTrigger)
            lruListLongWarn(loops);

        return 0L;
    }

    private void lruListLongWarn(int loops)
    {
        if (lastLruWarn + 10000L < System.currentTimeMillis())
        {
            lastLruWarn = System.currentTimeMillis();
            LOGGER.warn("Degraded OHC performance! LRU list very long - check OHC hash table size " +
                        "({} LRU links required to find hash entry) " +
                        "(this message will reappear in 10 seconds if the problem persists)",
                        loops);
        }
    }

    private boolean compareKey(long hashEntryAdr, BytesSource keySource, long serKeyLen)
    {
        if (hashEntryAdr == 0L)
            return false;

        long blkOff = OFF_DATA_IN_FIRST;
        long blkAdr = hashEntryAdr;

        // array optimized version
        if (keySource.hasArray())
        {
            byte[] arr = keySource.array();
            int arrOff = keySource.arrayOffset();
            for (int p = 0; p < serKeyLen; )
            {
                if (serKeyLen - p >= 8)
                {
                    long lSer = uns.getLong(blkAdr + blkOff);
                    long lKey = uns.getLongFromByteArray(arr, arrOff + p);
                    if (lSer != lKey)
                        return false;
                    blkOff += 8;
                    p += 8;
                }
                else
                {
                    byte bSer = uns.getByte(blkAdr + blkOff++);
                    byte bKey = keySource.getByte(p++);

                    if (bSer != bKey)
                        return false;
                }

                if (blkOff == blockSize)
                {
                    blkAdr = getNextBlock(blkAdr);
                    blkOff = OFF_DATA_IN_NEXT;
                }
            }

            return true;
        }

        // last-resort byte-by-byte compare
        for (int p = 0; p < serKeyLen; p++)
        {
            if (blkAdr == 0L)
                return false;

            byte bSer = uns.getByte(blkAdr + blkOff++);
            byte bKey = keySource.getByte(p);

            if (bSer != bKey)
                return false;

            if (blkOff == blockSize)
            {
                blkAdr = getNextBlock(blkAdr);
                blkOff = OFF_DATA_IN_NEXT;
            }
        }

        return true;
    }

    void writeValueToSink(long hashEntryAdr, BytesSink valueSink)
    {
        if (hashEntryAdr == 0L)
            return;

        long serKeyLen = uns.getLongVolatile(hashEntryAdr + OFF_HASH_KEY_LENGTH);
        if (serKeyLen < 0L)
            throw new InternalError();
        long valueLen = uns.getLongVolatile(hashEntryAdr + OFF_VALUE_LENGTH);
        if (valueLen < 0L)
            throw new InternalError();
        long blkAdr = hashEntryAdr;
        long blkOff;

        serKeyLen = roundUp(serKeyLen);

        // skip key
        if (serKeyLen >= firstBlockDataSpace)
        {
            serKeyLen -= firstBlockDataSpace;
            blkAdr = getNextBlock(hashEntryAdr);

            while (serKeyLen >= nextBlockDataSpace && blkAdr != 0L)
            {
                serKeyLen -= nextBlockDataSpace;
                blkAdr = getNextBlock(blkAdr);
            }

            blkOff = OFF_DATA_IN_NEXT + serKeyLen;
        }
        else
            blkOff = OFF_DATA_IN_FIRST + serKeyLen;

        if (valueLen > Integer.MAX_VALUE)
            throw new IllegalStateException("integer overflow");
        valueSink.setSize((int) valueLen);

        for (int p = 0; blkAdr != 0L && p < valueLen; p++)
        {
            byte b = uns.getByte(blkAdr + blkOff++);
            if (blkOff == blockSize)
            {
                blkAdr = getNextBlock(blkAdr);
                blkOff = OFF_DATA_IN_NEXT;
            }
            valueSink.putByte(p, b);
        }
    }

    static long roundUp(long val)
    {
        long rem = val & 7;
        if (rem != 0)
            val += 8L - rem;
        return val;
    }

    static long roundUpRel(long val)
    {
        long rem = val & 7;
        return rem != 0 ? 8L - rem : 0L;
    }

    void updatePartitionLRU(long partitionAdr, long hashEntryAdr)
    {
        if (partitionAdr == 0L || hashEntryAdr == 0L)
            return;

        long lruHead = hashPartitionAccess.getLRUHead(partitionAdr);
        if (lruHead != hashEntryAdr)
        {
            removeFromPartitionLRU(partitionAdr, hashEntryAdr, lruHead);
            addAsPartitionLRUHead(partitionAdr, hashEntryAdr, lruHead);
        }
    }

    void addAsPartitionLRUHead(long partitionAdr, long hashEntryAdr)
    {
        long lruHead = hashPartitionAccess.getLRUHead(partitionAdr);
        addAsPartitionLRUHead(partitionAdr, hashEntryAdr, lruHead);
    }

    void addAsPartitionLRUHead(long partitionAdr, long hashEntryAdr, long lruHead)
    {
        if (partitionAdr == 0L || hashEntryAdr == 0L)
            return;

        if (lruHead != 0L)
        {
            setLRUNext(hashEntryAdr, lruHead);
            setLRUPrevious(lruHead, hashEntryAdr);
        }
        else
            setLRUNext(hashEntryAdr, 0L);
        setLRUPrevious(hashEntryAdr, 0L);

        hashPartitionAccess.setLRUHead(partitionAdr, hashEntryAdr);
    }

    void removeFromPartitionLRU(long partitionAdr, long hashEntryAdr)
    {
        long lruHead = hashPartitionAccess.getLRUHead(partitionAdr);
        removeFromPartitionLRU(partitionAdr, hashEntryAdr, lruHead);
    }

    void removeFromPartitionLRU(long partitionAdr, long hashEntryAdr, long lruHead)
    {
        if (partitionAdr == 0L || hashEntryAdr == 0L)
            return;

        long entryPrev = getLRUPrevious(hashEntryAdr);
        long entryNext = getLRUNext(hashEntryAdr);

        if (entryNext != 0L)
            setLRUPrevious(entryNext, entryPrev);

        if (entryPrev != 0L)
            setLRUNext(entryPrev, entryNext);

        if (lruHead == hashEntryAdr)
            hashPartitionAccess.setLRUHead(partitionAdr, entryNext);

        // we can leave the LRU next+previous pointers in hashEntryAdr alone
    }

    void lockEntry(long hashEntryAdr)
    {
        if (hashEntryAdr != 0L)
            uns.lock(hashEntryAdr + OFF_ENTRY_LOCK);
    }

    void unlockEntry(long hashEntryAdr)
    {
        if (hashEntryAdr != 0L)
            uns.unlock(hashEntryAdr + OFF_ENTRY_LOCK);
    }

    private long getNextBlock(long blockAdr)
    {
        if (blockAdr == 0L)
            return 0L;
        blockAdr = uns.getLongVolatile(blockAdr + OFF_NEXT_BLOCK);
        if (blockAdr == 0L)
            throw new NullPointerException();
        return blockAdr;
    }

    long getLRUNext(long hashEntryAdr)
    {
        return hashEntryAdr != 0L ? uns.getLongVolatile(hashEntryAdr + OFF_LRU_NEXT) : 0L;
    }

    long getLRUPrevious(long hashEntryAdr)
    {
        return hashEntryAdr != 0L ? uns.getLongVolatile(hashEntryAdr + OFF_LRU_PREVIOUS) : 0L;
    }

    void setLRUPrevious(long hashEntryAdr, long previousAdr)
    {
        if (hashEntryAdr != 0L)
            uns.putLongVolatile(hashEntryAdr + OFF_LRU_PREVIOUS, previousAdr);
    }

    void setLRUNext(long hashEntryAdr, long nextAdr)
    {
        if (hashEntryAdr != 0L)
            uns.putLongVolatile(hashEntryAdr + OFF_LRU_NEXT, nextAdr);
    }

    void removeAll()
    {
        for (int h = 0; h < hashTableSize; h++)
        {
            long lruHead;
            long partitionAdr = hashPartitionAccess.lockPartitionForHash(h);
            try
            {
                lruHead = hashPartitionAccess.getLRUHead(partitionAdr);
                hashPartitionAccess.setLRUHead(partitionAdr, 0L);
            }
            finally
            {
                hashPartitionAccess.unlockPartition(partitionAdr);
            }

            for (long hashEntryAdr = lruHead; hashEntryAdr != 0L; hashEntryAdr = getLRUNext(hashEntryAdr))
            {
                lockEntry(hashEntryAdr);
                freeBlocks.freeChain(hashEntryAdr);
                // do NOT unlock - data block might be used elsewhere
            }
        }
    }

    int[] calcLruListLengths()
    {
        int[] ll = new int[hashTableSize];
        for (int h = 0; h < ll.length; h++)
        {
            long partitionAdr = hashPartitionAccess.lockPartitionForHash(h);
            try
            {
                int l = 0;
                for (long hashEntryAdr = hashPartitionAccess.getLRUHead(partitionAdr); hashEntryAdr != 0L; hashEntryAdr = getLRUNext(hashEntryAdr))
                    l++;
                ll[h] = l;
            }
            finally
            {
                hashPartitionAccess.unlockPartition(partitionAdr);
            }
        }
        return ll;
    }

    InputStream readKeyFrom(long hashEntryAdr)
    {
        return new HashEntryInput(hashEntryAdr, false);
    }

    InputStream readValueFrom(long hashEntryAdr)
    {
        return new HashEntryInput(hashEntryAdr, true);
    }

    <V> void valueToHashEntry(long hashEntryAdr, CacheSerializer<V> valueSerializer, V v) throws IOException
    {
        valueSerializer.serialize(v, new HashEntryOutput(hashEntryAdr));
    }

    void hotN(int hash, HashEntryCallback heCb)
    {
        long partitionAdr = hashPartitionAccess.lockPartitionForHash(hash);
        try
        {
            for (long hashEntryAdr = hashPartitionAccess.getLRUHead(partitionAdr); hashEntryAdr != 0L; hashEntryAdr = getLRUNext(hashEntryAdr))
                heCb.hashEntry(hashEntryAdr);
        }
        finally
        {
            hashPartitionAccess.unlockPartition(partitionAdr);
        }
    }

    final class HashEntryOutput extends OutputStream
    {
        private long blkAdr;
        private long blkOff;
        private long available;

        HashEntryOutput(long hashEntryAdr)
        {
            if (hashEntryAdr == 0L)
                throw new NullPointerException();

            long serKeyLen = uns.getLongVolatile(hashEntryAdr + OFF_HASH_KEY_LENGTH);
            if (serKeyLen < 0L)
                throw new InternalError();
            long valueLen = uns.getLongVolatile(hashEntryAdr + OFF_VALUE_LENGTH);
            if (valueLen < 0L)
                throw new InternalError();
            long blkAdr = hashEntryAdr;
            long blkOff;

            serKeyLen = roundUp(serKeyLen);

            // skip key
            if (serKeyLen >= firstBlockDataSpace)
            {
                serKeyLen -= firstBlockDataSpace;
                blkAdr = getNextBlock(hashEntryAdr);

                while (serKeyLen >= nextBlockDataSpace && blkAdr != 0L)
                {
                    serKeyLen -= nextBlockDataSpace;
                    blkAdr = getNextBlock(blkAdr);
                }

                blkOff = OFF_DATA_IN_NEXT + serKeyLen;
            }
            else
                blkOff = OFF_DATA_IN_FIRST + serKeyLen;

            if (valueLen > Integer.MAX_VALUE)
                throw new IllegalStateException("integer overflow");

            this.blkAdr = blkAdr;
            this.blkOff = blkOff;
            this.available = valueLen;
        }

        public void write(byte[] b, int off, int len) throws IOException
        {
            if (b == null)
                throw new NullPointerException();
            if (off < 0 || off + len > b.length || len < 0)
                throw new ArrayIndexOutOfBoundsException();

            if (len > available)
                len = (int) available;
            if (len <= 0)
                throw new EOFException();

            while (len > 0)
            {
                int blkAvail = (int) (blockSize - blkOff);
                int r = (blkAvail >= len) ? len : blkAvail;
                if (r > available)
                    r = (int) available;
                if (available <= 0)
                    throw new EOFException();

                uns.copyMemory(b, off, blkAdr + blkOff, r);
                len -= r;
                off += r;
                available -= r;

                if (blkOff == blockSize)
                {
                    blkAdr = getNextBlock(blkAdr);
                    blkOff = OFF_DATA_IN_NEXT;
                }
            }
        }

        public void write(int b) throws IOException
        {
            if (available < 1)
                throw new EOFException();

            available--;

            uns.putByte(blkAdr + blkOff++, (byte) b);
            if (blkOff == blockSize)
            {
                blkAdr = getNextBlock(blkAdr);
                blkOff = OFF_DATA_IN_NEXT;
            }
        }
    }

    final class HashEntryInput extends InputStream
    {
        private long blkAdr;
        private long blkOff;
        private long available;

        HashEntryInput(long hashEntryAdr, boolean value)
        {
            if (hashEntryAdr == 0L)
                throw new NullPointerException();

            long serKeyLen = uns.getLongVolatile(hashEntryAdr + OFF_HASH_KEY_LENGTH);
            if (serKeyLen < 0L)
                throw new InternalError();
            long valueLen = uns.getLongVolatile(hashEntryAdr + OFF_VALUE_LENGTH);
            if (valueLen < 0L)
                throw new InternalError();
            long blkAdr = hashEntryAdr;
            long blkOff;

            if (value)
            {

                serKeyLen = roundUp(serKeyLen);

                // skip key
                if (serKeyLen >= firstBlockDataSpace)
                {
                    serKeyLen -= firstBlockDataSpace;
                    blkAdr = getNextBlock(hashEntryAdr);

                    while (serKeyLen >= nextBlockDataSpace && blkAdr != 0L)
                    {
                        serKeyLen -= nextBlockDataSpace;
                        blkAdr = getNextBlock(blkAdr);
                    }

                    blkOff = OFF_DATA_IN_NEXT + serKeyLen;
                }
                else
                    blkOff = OFF_DATA_IN_FIRST + serKeyLen;
            }
            else
            {
                blkOff = 0L;
            }

            if (valueLen > Integer.MAX_VALUE)
                throw new IllegalStateException("integer overflow");

            this.blkAdr = blkAdr;
            this.blkOff = blkOff;
            this.available = value ? valueLen : serKeyLen;
        }

        public int available() throws IOException
        {
            return available > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) available;
        }

        public long skip(long n) throws IOException
        {
            return super.skip(n);
        }

        public int read(byte[] b, int off, int len) throws IOException
        {
            if (b == null)
                throw new NullPointerException();
            if (off < 0 || off + len > b.length || len < 0)
                throw new ArrayIndexOutOfBoundsException();

            if (len > available)
                len = (int) available;
            if (len <= 0)
                return -1;

            int rd = 0;

            while (len > 0)
            {
                int blkAvail = (int) (blockSize - blkOff);
                int r = (blkAvail >= len) ? len : blkAvail;
                if (r > available)
                    r = (int) available;
                if (r <= 0)
                    break;

                uns.copyMemory(blkAdr + blkOff, b, off, r);
                rd += r;
                off += r;
                len -= r;
                available -= r;

                if (blkOff == blockSize)
                {
                    blkAdr = getNextBlock(blkAdr);
                    blkOff = OFF_DATA_IN_NEXT;
                }
            }

            return rd;
        }

        public int read() throws IOException
        {
            if (available < 1)
                return -1;

            available--;

            byte b = uns.getByte(blkAdr + blkOff++);
            if (blkOff == blockSize)
            {
                blkAdr = getNextBlock(blkAdr);
                blkOff = OFF_DATA_IN_NEXT;
            }

            return b & 0xff;
        }
    }

    static abstract class HashEntryCallback
    {
        abstract void hashEntry(long hashEntryAdr);
    }
}