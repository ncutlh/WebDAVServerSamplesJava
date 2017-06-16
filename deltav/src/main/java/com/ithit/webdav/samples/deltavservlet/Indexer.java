package com.ithit.webdav.samples.deltavservlet;

import com.ithit.webdav.server.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.RecursiveAction;

/**
 * Indexes files in storage using Apache Lucene engine for indexing and Apache Tika.
 */
class Indexer extends RecursiveAction {
    static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final int TASK_INTERVAL = 30 * 1000;
    static final String ID = "id";
    static final String NAME = "name";
    static final String CONTENTS = "contents";
    private IndexWriter indexWriter;
    private List<HierarchyItemImpl> files;
    private Logger logger;
    private Tika tika;
    private final static int BATCH_SIZE = 100;

    /**
     * Create instance of Indexer file.
     * @param iw {@link IndexWriter} Lucene index writer.
     * @param files List of the file to index.
     * @param logger {@link Logger}.
     * @param tika {@link Tika} to read content.
     */
    Indexer(IndexWriter iw, List<HierarchyItemImpl> files, Logger logger, Tika tika) {
        this.indexWriter = iw;
        this.files = files;
        this.logger = logger;
        this.tika = tika;
    }

    @Override
    protected void compute() {
        if (files.size() > BATCH_SIZE) {
            List<Indexer> tasks = new ArrayList<>();
            List<List<HierarchyItemImpl>> partitioned = chopped(files, BATCH_SIZE);
            for (List<HierarchyItemImpl> sublist : partitioned) {
                tasks.add(new Indexer(indexWriter, sublist, logger, tika));
            }
            invokeAll(tasks);
        } else {
            for (HierarchyItemImpl f : files) {
                indexFile(f.getName(), f.getId(), null, f);
            }
        }
    }

    private static <T> List<List<T>> chopped(List<T> list, final int L) {
        List<List<T>> parts = new ArrayList<>();
        final int N = list.size();
        for (int i = 0; i < N; i += L) {
            parts.add(new ArrayList<>(
                    list.subList(i, Math.min(N, i + L)))
            );
        }
        return parts;
    }

    /**
     * Indexes file.
     * @param fileName File name to add to index.
     * @param currentId Current id of the file.
     * @param oldId Old id of the file if it was moved.
     * @param file {@link FileImpl} to index.
     */
    void indexFile(String fileName, Integer currentId, Integer oldId, HierarchyItemImpl file) {
        try {
            Field pathField = new StringField(ID, currentId.toString(), Field.Store.YES);
            Field nameField = new TextField(NAME, fileName, Field.Store.YES);
            Document doc = new Document();
            doc.add(pathField);
            doc.add(nameField);
            if (file instanceof FileImpl) {
                indexContent(currentId, (FileImpl) file, doc);
            }
            if (indexWriter.getConfig().getOpenMode() == IndexWriterConfig.OpenMode.CREATE) {
                indexWriter.addDocument(doc);
            } else {
                indexWriter.updateDocument(new Term(ID, oldId != null ? oldId.toString() : currentId.toString()), doc);
            }
        } catch (Exception e) {
            logger.logError("Error while indexing file: " + currentId, e);
        }
    }

    /**
     * Indexes content of the file.
     * @param currentId File id.
     * @param file {@link FileImpl}
     * @param doc Apache Lucene {@link Document}
     */
    private void indexContent(Integer currentId, FileImpl file, Document doc) {
        InputStream stream = null;
        try {
            stream = file.getFileContentToIndex(currentId);
            if (stream != null) {
                Metadata metadata = new Metadata();
                String content = tika.parseToString(stream, metadata, MAX_CONTENT_LENGTH);
                doc.add(new TextField(CONTENTS, content, Field.Store.YES));
            }
        } catch (Exception ex) {
            logger.logError("Cannot index content.", ex);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    logger.logError("Error while indexing file content: " + currentId, e);
                }
            }
        }
    }

    /**
     * Deletes specified file information from the index.
     * @param file {@link FileImpl} to delete from index.
     */
    void deleteIndex(HierarchyItemImpl file) {
        try {
            indexWriter.deleteDocuments(new Term(ID, String.valueOf(file.getId())));
        } catch (Exception e) {
            logger.logDebug("Cannot delete index for the file: " + file.getId());
        }
    }

    /**
     * Timer task implementation to commit index changes from time to time.
     */
    static class CommitTask extends TimerTask {

        private IndexWriter indexWriter;
        private Logger logger;

        /**
         * Creates instance of {@link CommitTask}.
         * @param indexWriter {@link IndexWriter} Lucene index writer.
         * @param logger {@link Logger}.
         */
        CommitTask(IndexWriter indexWriter, Logger logger) {
            this.indexWriter = indexWriter;
            this.logger = logger;
        }

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {
            try {
                indexWriter.commit();
            } catch (IOException e) {
                logger.logError("Cannot commit.", e);
            }
        }

        /**
         * Schedule timer executions at the specified Interval.
         * @param interval Timer interval.
         */
        void schedule(Integer interval) {
            Timer timer = new Timer(true);
            timer.scheduleAtFixedRate(this, 0, interval == null ? TASK_INTERVAL : interval * 1000);
        }
    }
}
