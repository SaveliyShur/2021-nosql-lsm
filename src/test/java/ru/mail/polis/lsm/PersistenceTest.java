package ru.mail.polis.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import utils.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.Utils.*;

class PersistenceTest {

    /*
        Create value, save on a disk, read value from disk
     */
    @Test
    void basicSave(@TempDir Path data) throws IOException {
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            dao.upsert(Record.of(key(1), value(1)));
        }

        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            Iterator<Record> range = dao.range(null, null);
            assertTrue(range.hasNext());

            Record record = range.next();
            assertEquals(key(1), record.getKey());
            assertEquals(value(1), record.getValue());
        }
    }

    /*
        Create value in memory, chech in memory
        Check normal close without Range
     */
    @Test
    void basicSaveCheckInMemory(@TempDir Path data) throws IOException {
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            dao.upsert(Record.of(key(1), value(1)));

            Iterator<Record> range = dao.range(null, null);
            assertTrue(range.hasNext());

            Record record = range.next();
            assertEquals(key(1), record.getKey());
            assertEquals(value(1), record.getValue());
        }
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            dao.upsert(Record.of(key(1), value(1)));
        }
    }

    @Test
    void fs(@TempDir Path data) throws IOException {
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            dao.upsert(Record.of(key(1), value(1)));
        }

        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            Iterator<Record> range = dao.range(null, null);
            assertTrue(range.hasNext());

            Record record = range.next();
            assertEquals(key(1), record.getKey());
            assertEquals(value(1), record.getValue());
        }

        recursiveDelete(data);

        assertFalse(Files.exists(data));
        Files.createDirectory(data);

        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            assertFalse(dao.range(null, null).hasNext());
        }
    }

    @Test
    void remove(@TempDir Path data) throws IOException {
        // Reference value
        ByteBuffer key = wrap("SOME_KEY");
        ByteBuffer value = wrap("SOME_VALUE");

        // Create dao and fill data
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            dao.upsert(Record.of(key, value));
            Iterator<Record> range = dao.range(null, null);

            assertTrue(range.hasNext());
            assertEquals(value, range.next().getValue());
        }

        // Load data and check
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            Iterator<Record> range = dao.range(null, null);
            assertTrue(range.hasNext());
            assertEquals(value, range.next().getValue());

            // Remove data and flush
            dao.upsert(Record.tombstone(key));
        }

        // Load and check not found
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            Iterator<Record> range = dao.range(null, null);

            assertFalse(range.hasNext());
        }
    }

    @Test
    void replaceWithClose(@TempDir Path data) throws Exception {
        ByteBuffer key = wrap("KEY");
        ByteBuffer value = wrap("VALUE_1");
        ByteBuffer value2 = wrap("VALUE_2");

        // Initial insert
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            dao.upsert(Record.of(key, value));

            Iterator<Record> range = dao.range(null, null);
            assertTrue(range.hasNext());
            assertEquals(value, range.next().getValue());
        }

        // Reopen
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            Iterator<Record> range = dao.range(null, null);
            assertTrue(range.hasNext());
            assertEquals(value, range.next().getValue());

            // Replace
            dao.upsert(Record.of(key, value2));

            Iterator<Record> range2 = dao.range(null, null);
            assertTrue(range2.hasNext());
            assertEquals(value2, range2.next().getValue());
        }

        // Reopen
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            // Last value should win
            Iterator<Record> range2 = dao.range(null, null);
            assertTrue(range2.hasNext());
            assertEquals(value2, range2.next().getValue());
        }
    }

    @Test
    void burn(@TempDir Path data) throws IOException {
        ByteBuffer key = wrap("FIXED_KEY");

        int overwrites = 100;
        for (int i = 0; i < overwrites; i++) {
            ByteBuffer value = value(i);
            try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
                dao.upsert(Record.of(key, value));
                assertEquals(value, dao.range(key, null).next().getValue());
            }

            // Check
            try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
                assertEquals(value, dao.range(key, null).next().getValue());
            }
        }
    }

    @Test
    void hugeRecords(@TempDir Path data) throws IOException {
        // Reference value
        int size = 1024 * 1024;
        byte[] suffix = sizeBasedRandomData(size);
        int recordsCount = (int) (TestDaoWrapper.MAX_HEAP * 15 / size);

        prepareHugeDao(data, recordsCount, suffix);

        // Check
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            Iterator<Record> range = dao.range(null, null);

            for (int i = 0; i < recordsCount; i++) {
                verifyNext(suffix, range, i);
            }

            assertFalse(range.hasNext());
        }
    }

/*
    @Test
    void hugeRecordsSearch(@TempDir Path data) throws IOException {
        // Reference value
        int size = 1024 * 1024;
        byte[] suffix = sizeBasedRandomData(size);
        int recordsCount = (int) (TestDaoWrapper.MAX_HEAP * 15 / size);

        prepareHugeDao(data, recordsCount, suffix);

        // Check
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            int searchStep = 4;

            for (int i = 0; i < recordsCount / searchStep; i++) {
                ByteBuffer keyFrom = keyWithSuffix(i * searchStep, suffix);
                ByteBuffer keyTo = keyWithSuffix(i * searchStep + searchStep, suffix);

                Iterator<Record> range = dao.range(keyFrom, keyTo);
                for (int j = 0; j < searchStep; j++) {
                    verifyNext(suffix, range, i * searchStep + j);
                }
                assertFalse(range.hasNext());
            }
        }
    }
*/

    @Test
    void burnAndCompact(@TempDir Path data) throws IOException {
        int overwrites = 2;
        for (int i = 0; i < overwrites; i++) {
            try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
                for (int j = 0; j < 10; j++) {
                    Record record = Record.of(wrap("KEY_" + j), wrap("VALUE_" + j));
                    dao.upsert(record);
                }
            }
        }

        long fileNumber = getNumberFiles(data);
        assertEquals(2L, fileNumber, "Files not compact");
    }

    private void verifyNext(byte[] suffix, Iterator<Record> range, int index) {
        ByteBuffer key = keyWithSuffix(index, suffix);
        ByteBuffer value = valueWithSuffix(index, suffix);

        Record next = range.next();

        assertEquals(key, next.getKey());
        assertEquals(value, next.getValue());
    }

    private void prepareHugeDao(@TempDir Path data, int recordsCount, byte[] suffix) throws IOException {
        try (DAO dao = TestDaoWrapper.create(new DAOConfig(data))) {
            for (int i = 0; i < recordsCount; i++) {
                ByteBuffer key = keyWithSuffix(i, suffix);
                ByteBuffer value = valueWithSuffix(i, suffix);

                dao.upsert(Record.of(key, value));
            }
        }
    }

    private long getNumberFiles(Path path) {
        long count = 0;

        try (Stream<Path> files = Files.list(path)) {
            count = files.count();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return count;
    }
}
