
package com.jdvn.setl.geos.processors.db;

import org.apache.avro.Schema;
import org.apache.nifi.avro.AvroTypeUtil;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.serialization.record.ResultSetRecordSet;
import com.jdvn.setl.geos.processors.db.JdbcCommon;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.jdvn.setl.geos.processors.db.JdbcCommon.AvroConversionOptions;
import com.jdvn.setl.geos.processors.db.JdbcCommon.ResultSetRowCallback;

public class RecordPostGISWriter implements SqlWriter {

    private final RecordSetWriterFactory recordSetWriterFactory;
    private final AtomicReference<WriteResult> writeResultRef;
    private final JdbcCommon.AvroConversionOptions options;
    private final int maxRowsPerFlowFile;
    private final Map<String, String> originalAttributes;
    private ResultSetRecordSet fullRecordSet;
    private RecordSchema writeSchema;
    private String mimeType;

    public RecordPostGISWriter(RecordSetWriterFactory recordSetWriterFactory, AvroConversionOptions options, int maxRowsPerFlowFile, Map<String, String> originalAttributes) {
        this.recordSetWriterFactory = recordSetWriterFactory;
        this.writeResultRef = new AtomicReference<>();
        this.maxRowsPerFlowFile = maxRowsPerFlowFile;
        this.options = options;
        this.originalAttributes = originalAttributes;
    }

    @Override
    public long writeResultSet(ResultSet resultSet, OutputStream outputStream, ComponentLog logger, ResultSetRowCallback callback) throws Exception {
        final RecordSet recordSet;
        try {
            if (fullRecordSet == null) {
                final Schema avroSchema = JdbcCommon.createSchema(resultSet, options);
                final RecordSchema recordAvroSchema = AvroTypeUtil.createSchema(avroSchema);
                fullRecordSet = new ResultSetRecordSetWithCallback(resultSet, recordAvroSchema, callback, options.getDefaultPrecision(), options.getDefaultScale(), options.isUseLogicalTypes());
                writeSchema = recordSetWriterFactory.getSchema(originalAttributes, fullRecordSet.getSchema());
            }
            recordSet = (maxRowsPerFlowFile > 0) ? fullRecordSet.limit(maxRowsPerFlowFile) : fullRecordSet;

        } catch (final SQLException | SchemaNotFoundException | IOException e) {
            throw new ProcessException(e);
        }
        try (final RecordSetWriter resultSetWriter = recordSetWriterFactory.createWriter(logger, writeSchema, outputStream, Collections.emptyMap())) {
            writeResultRef.set(resultSetWriter.write(recordSet));
            if (mimeType == null) {
                mimeType = resultSetWriter.getMimeType();
            }
            return writeResultRef.get().getRecordCount();
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public Map<String, String> getAttributesToAdd() {
        Map<String, String> attributesToAdd = new HashMap<>();
        attributesToAdd.put(CoreAttributes.MIME_TYPE.key(), mimeType);

        // Add any attributes from the record writer (if present)
        final WriteResult result = writeResultRef.get();
        if (result != null) {
            if (result.getAttributes() != null) {
                attributesToAdd.putAll(result.getAttributes());
            }

            attributesToAdd.put("record.count", String.valueOf(result.getRecordCount()));
        }
        return attributesToAdd;
    }

    @Override
    public void updateCounters(ProcessSession session) {
        final WriteResult result = writeResultRef.get();
        if (result != null) {
            session.adjustCounter("Records Written", result.getRecordCount(), false);
        }
    }

    @Override
    public void writeEmptyResultSet(OutputStream outputStream, ComponentLog logger) throws IOException {
        try (final RecordSetWriter resultSetWriter = recordSetWriterFactory.createWriter(logger, writeSchema, outputStream, Collections.emptyMap())) {
            mimeType = resultSetWriter.getMimeType();
            resultSetWriter.beginRecordSet();
            resultSetWriter.finishRecordSet();
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    private static class ResultSetRecordSetWithCallback extends ResultSetRecordSet {

        private final ResultSetRowCallback callback;

        ResultSetRecordSetWithCallback(ResultSet rs, RecordSchema readerSchema, ResultSetRowCallback callback,
                                       final int defaultPrecision, final int defaultScale, final boolean useLogicalTypes) throws SQLException {
            super(rs, readerSchema, defaultPrecision, defaultScale, useLogicalTypes);
            this.callback = callback;
        }

        @Override
        public Record next() throws IOException {
            try {
                if (hasMoreRows()) {
                    ResultSet rs = getResultSet();
                    final Record record = createRecord(rs);
                    if (callback != null) {
                        callback.processRow(rs);
                    }
                    setMoreRows(rs.next());
                    return record;
                } else {
                    return null;
                }
            } catch (final SQLException e) {
                throw new IOException("Could not obtain next record from ResultSet", e);
            }
        }
    }
}
