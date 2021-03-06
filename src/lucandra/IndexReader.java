/**
 * Copyright T Jake Luciani
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
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
package lucandra;

import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

import lucandra.cluster.CassandraIndexManager;
import lucandra.serializers.thrift.DocumentMetadata;
import lucandra.serializers.thrift.ThriftTerm;

import com.google.common.collect.MapMaker;

import org.apache.cassandra.db.*;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.*;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.OpenBitSet;

import solandra.SolandraFieldSelector;

public class IndexReader extends org.apache.lucene.index.IndexReader
{
    private final static int                                numDocs       = CassandraIndexManager.maxDocsPerShard;
    private final static byte                               defaultNorm   = Similarity.encodeNorm(1.0f);

    private final static Directory                          mockDirectory = new RAMDirectory();
    static
    {

        try
        {
            new IndexWriter(mockDirectory, new SimpleAnalyzer(), true, MaxFieldLength.LIMITED);
        }
        catch (CorruptIndexException e)
        {
            throw new RuntimeException(e);
        }
        catch (LockObtainFailedException e)
        {
            throw new RuntimeException(e);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private final static ThreadLocal<String>                indexName     = new ThreadLocal<String>();
    private final static ThreadLocal<ReaderCache>           activeCache   = new ThreadLocal<ReaderCache>();
    private final static ConcurrentMap<String, ReaderCache> globalCache   = new MapMaker().makeMap();

    private static final Logger                             logger        = Logger.getLogger(IndexReader.class);

    public IndexReader(String name)
    {
        super();
        setIndexName(name);
    }

    public synchronized IndexReader reopen() throws CorruptIndexException, IOException
    {
        clearCache();

        return this;
    }

    @Override
    public synchronized IndexReader reopen(boolean openReadOnly) throws CorruptIndexException, IOException
    {
        return reopen();
    }

    @Override
    public synchronized IndexReader reopen(IndexCommit commit) throws CorruptIndexException, IOException
    {
        return reopen();
    }

    public void clearCache()
    {

        String activeIndex = getIndexName();

        if (activeIndex != null)
        {
            globalCache.remove(activeIndex);
        }

        activeCache.remove();
    }

    public ReaderCache getCache() throws IOException
    {
        String activeIndex = getIndexName();

        if (activeIndex == null)
            throw new IllegalStateException();

        ReaderCache cache = activeCache.get();

        if (cache != null)
            return cache;
        else
            cache = globalCache.get(activeIndex);

        if (cache == null)
        {
            synchronized (activeIndex.intern())
            {
                cache = globalCache.get(activeIndex);
                if (cache == null)
                {
                    cache = new ReaderCache(activeIndex);
                    globalCache.put(activeIndex, cache);
                }
            }
        }

        activeCache.set(cache);

        return cache;
    }

    protected void doClose() throws IOException
    {
        clearCache();
    }

    protected void doCommit() throws IOException
    {
        clearCache();
    }

    protected void doDelete(int arg0) throws CorruptIndexException, IOException
    {

    }

    protected void doSetNorm(int arg0, String arg1, byte arg2) throws CorruptIndexException, IOException
    {

    }

    protected void doUndeleteAll() throws CorruptIndexException, IOException
    {

    }

    public int docFreq(Term term) throws IOException
    {

        LucandraTermInfo[] docs = getCache().termCache.get(term);

        if (docs != null)
            return docs.length;

        LucandraTermEnum termEnum = new LucandraTermEnum(this);

        if (termEnum.skipTo(term) && termEnum.term().equals(term))
        {
            return termEnum.docFreq();
        }

        return 0;
    }

    public Document document(int docNum, FieldSelector selector) throws CorruptIndexException, IOException
    {

        Map<Integer, Document> documentCache = getCache().documents;
        Document doc = documentCache.get(docNum);

        if (doc != null)
        {
            if (logger.isDebugEnabled())
                logger.debug("Found doc in cache");

            return doc;
        }

        String indexName = getIndexName();

        List<ByteBuffer> fieldNames = null;

        Map<Integer, ByteBuffer> keyMap = new HashMap<Integer, ByteBuffer>();
        keyMap.put(docNum, CassandraUtils.hashKeyBytes(indexName.getBytes("UTF-8"), CassandraUtils.delimeterBytes, Integer
                .toHexString(docNum).getBytes("UTF-8")));

        // Special field selector used to carry list of other docIds to cache in
        // Parallel for Solr Performance
        if (selector != null && selector instanceof SolandraFieldSelector)
        {

            List<Integer> otherDocIds = ((SolandraFieldSelector) selector).getOtherDocsToCache();
            fieldNames = ((SolandraFieldSelector) selector).getFieldNames();

            if (logger.isDebugEnabled())
                logger.debug("Going to bulk load " + otherDocIds.size() + " documents");

            for (Integer otherDocNum : otherDocIds)
            {
                if (otherDocNum == docNum)
                    continue;

                if (documentCache.containsKey(otherDocNum))
                    continue;

                byte[] docKey = Integer.toHexString(otherDocNum).getBytes("UTF-8");

                if (docKey == null)
                    continue;

                keyMap.put(otherDocNum, CassandraUtils.hashKeyBytes(indexName.getBytes("UTF-8"),
                        CassandraUtils.delimeterBytes, docKey));
            }
        }

        ColumnParent columnParent = new ColumnParent();
        columnParent.setColumn_family(CassandraUtils.docColumnFamily);

        long start = System.currentTimeMillis();

        try
        {

            List<Row> rows = null;
            List<ReadCommand> readCommands = new ArrayList<ReadCommand>();
            for (ByteBuffer key : keyMap.values())
            {

                if (fieldNames == null || fieldNames.size() == 0)
                {
                    // get all columns ( except this skips meta info )
                    readCommands.add(new SliceFromReadCommand(CassandraUtils.keySpace, key, columnParent,
                            ByteBufferUtil.EMPTY_BYTE_BUFFER, CassandraUtils.finalTokenBytes, false, Integer.MAX_VALUE));
                }
                else
                {
                    readCommands
                            .add(new SliceByNamesReadCommand(CassandraUtils.keySpace, key, columnParent, fieldNames));
                }
            }

            rows = CassandraUtils.robustRead(CassandraUtils.consistency, readCommands.toArray(new ReadCommand[]{}));


            // allow lookup by row
            Map<ByteBuffer, Row> rowMap = new HashMap<ByteBuffer, Row>(keyMap.size());
            for (Row row : rows)
            {
                rowMap.put(row.key.key, row);
            }

            for (Map.Entry<Integer, ByteBuffer> key : keyMap.entrySet())
            {
                Document cacheDoc = new Document();

                Row row = rowMap.get(key.getValue());

                if (row == null || row.cf == null)
                {
                    logger.warn("Missing document in multiget_slice for: "
                            + ByteBufferUtil.string(key.getValue(), CassandraUtils.UTF_8) + " " + rowMap);

                }
                else
                {
                    for (IColumn col : row.cf.getSortedColumns())
                    {

                        Field field = null;
                        String fieldName = ByteBufferUtil.string(col.name());

                        // Incase __META__ slips through
                        if (ByteBufferUtil.compare(col.name(), CassandraUtils.documentMetaFieldBytes.array()) == 0)
                        {
                            logger.warn("Filtering out __META__ key");
                            continue;
                        }

                        DocumentMetadata dm = lucandra.IndexWriter.fromBytesUsingThrift(col.value());
                                       
                        for(ThriftTerm term : dm.getTerms())
                        {
                            Fieldable f = null; 
                            
                            if( term.isSetLongVal() )
                            {
                                f =  new NumericField(term.getField()).setLongValue(term.getLongVal());            
                            }                                           
                            else if(term.isSetIs_binary())
                            {
                                if(term.is_binary)
                                    f = new Field(term.getField(), term.getText());
                                else 
                                    f = new Field(term.getField(), new String(term.getText()), Store.YES, Index.ANALYZED);
                            }
                            else
                                throw new RuntimeException("Malformed term");
                            
                            cacheDoc.add(f);

                        }                      
                    } 
                }

                // Mark the required doc
                if (key.getKey().equals(docNum))
                    doc = cacheDoc;

                // only cache complete docs
                if (fieldNames == null || fieldNames.size() == 0)
                    documentCache.put(key.getKey(), cacheDoc);

            }

            long end = System.currentTimeMillis();

            if (logger.isDebugEnabled())
                logger.debug("Document read took: " + (end - start) + "ms");

            return doc;

        }
        catch (Exception e)
        {
            throw new IOException(e);
        }

    }

    @Override
    public Object getCoreCacheKey()
    {

        try
        {
            return getCache().fieldCacheKey;
        }
        catch (IOException e)
        {
           throw new RuntimeException(e);
        }

    }
    
    public void addReaderFinishedListener(ReaderFinishedListener listener)
    {
        try
        {
            getCache().readerFinishedListeners.add(listener);
        }
        catch(IOException e)
        {
            throw new IOError(e);
        }
    }


    @Override
    public Collection getFieldNames(FieldOption fieldOption)
    {
        return Arrays.asList(new String[] {});
    }

    @Override
    public TermFreqVector getTermFreqVector(int docNum, String field) throws IOException
    {

        TermFreqVector termVector = new lucandra.TermFreqVector(getIndexName(), field, docNum);

        return termVector;
    }

    @Override
    public void getTermFreqVector(int arg0, TermVectorMapper arg1) throws IOException
    {
        throw new RuntimeException();
    }

    @Override
    public void getTermFreqVector(int arg0, String arg1, TermVectorMapper arg2) throws IOException
    {

        throw new RuntimeException();

    }

    @Override
    public TermFreqVector[] getTermFreqVectors(int arg0) throws IOException
    {
        throw new RuntimeException();
    }

    @Override
    public boolean hasDeletions()
    {

        return false;
    }

    @Override
    public boolean isDeleted(int arg0)
    {

        return false;
    }

    @Override
    public int maxDoc()
    {
        return numDocs + 1;
    }

    @Override
    public byte[] norms(String field) throws IOException
    {
        return getCache().fieldNorms.get(field);
    }

    @Override
    public void norms(String arg0, byte[] arg1, int arg2) throws IOException
    {

        throw new RuntimeException("This operation is not supported");

    }

    @Override
    public int numDocs()
    {

        return numDocs;
    }

    @Override
    public TermDocs termDocs(Term term) throws IOException
    {

        if (term == null)
            return new LucandraAllTermDocs(this);

        return super.termDocs(term);
    }

    @Override
    public TermDocs termDocs() throws IOException
    {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermPositions termPositions() throws IOException
    {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermEnum terms() throws IOException
    {
        return new LucandraTermEnum(this);
    }

    @Override
    public TermEnum terms(Term term) throws IOException
    {

        LucandraTermEnum termEnum = new LucandraTermEnum(this);

        termEnum.skipTo(term);

        return termEnum;
    }

    public void addDocumentNormalizations(LucandraTermInfo[] allDocs, String field, ReaderCache cache)
    {

        byte[] norms = cache.fieldNorms.get(field);
        OpenBitSet docHits = cache.docHits;

        for (LucandraTermInfo docInfo : allDocs)
        {

            int idx = docInfo.docId;

            if (idx > numDocs)
                throw new IllegalStateException("numDocs reached");

            Byte norm = docInfo.norm;

            if (norm == null)
                norm = defaultNorm;

            // Check for cached reads
            if (norms != null && norms.length > idx && norms[idx] == norm)
                continue;

            docHits.fastSet(idx);

            if (norms == null)
                norms = new byte[numDocs];

            norms[idx] = norm;
        }

        cache.fieldNorms.put(field, norms);
    }

    public String getIndexName()
    {
        String name = indexName.get();

        return name == null ? "" : name;
    }

    public void setIndexName(String name)
    {
        activeCache.remove();

        indexName.set(name);
    }

    @Override
    public Directory directory()
    {
        return mockDirectory;
    }

    @Override
    public long getVersion()
    {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean isOptimized()
    {
        return true;
    }

    @Override
    public boolean isCurrent()
    {
        return true;
    }

    public OpenBitSet getDocsHit()
    {
        try
        {
            return getCache().docHits;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    protected void doCommit(Map<String, String> arg0) throws IOException
    {
        // TODO Auto-generated method stub
        
    }

}
