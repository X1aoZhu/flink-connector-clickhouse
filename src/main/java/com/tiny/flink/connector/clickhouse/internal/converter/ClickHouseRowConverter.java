//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.tiny.flink.connector.clickhouse.internal.converter;

import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.RowType.RowField;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.util.Preconditions;
import ru.yandex.clickhouse.ClickHousePreparedStatement;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * @author tiny.wang
 */
public class ClickHouseRowConverter implements Serializable {

    private static final long serialVersionUID = 1L;

    private final RowType rowType;

    private final LogicalType[] fieldTypes;

    private final ClickHouseRowConverter.DeserializationConverter[] toFlinkConverters;

    private final ClickHouseRowConverter.SerializationConverter[] toClickHouseConverters;

    public ClickHouseRowConverter(RowType rowType) {
        this.rowType = Preconditions.checkNotNull(rowType);
        this.fieldTypes = rowType.getFields().stream().map(RowField::getType).toArray(LogicalType[]::new);
        this.toFlinkConverters = new ClickHouseRowConverter.DeserializationConverter[rowType.getFieldCount()];
        this.toClickHouseConverters = new ClickHouseRowConverter.SerializationConverter[rowType.getFieldCount()];

        for (int i = 0; i < rowType.getFieldCount(); ++i) {
            this.toFlinkConverters[i] = this.createToFlinkConverter(rowType.getTypeAt(i));
            this.toClickHouseConverters[i] = this.createToClickHouseConverter(this.fieldTypes[i]);
        }

    }

    public RowData toFlink(ResultSet resultSet) throws SQLException {
        GenericRowData genericRowData = new GenericRowData(this.rowType.getFieldCount());

        for (int pos = 0; pos < this.rowType.getFieldCount(); ++pos) {
            Object field = resultSet.getObject(pos + 1);
            genericRowData.setField(pos, this.toFlinkConverters[pos].deserialize(field));
        }

        return genericRowData;
    }

    public void toClickHouse(RowData rowData, ClickHousePreparedStatement statement) throws SQLException {
        for (int index = 0; index < rowData.getArity(); ++index) {
            if (!rowData.isNullAt(index)) {
                this.toClickHouseConverters[index].serialize(rowData, index, statement);
            } else {
                statement.setObject(index + 1, null);
            }
        }
    }

    protected ClickHouseRowConverter.DeserializationConverter createToFlinkConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case NULL:
                return (val) -> null;
            case BOOLEAN:
            case FLOAT:
            case DOUBLE:
            case INTERVAL_YEAR_MONTH:
            case INTERVAL_DAY_TIME:
            case INTEGER:
            case BIGINT:
            case BINARY:
            case VARBINARY:
                return (val) -> val;
            case TINYINT:
                return (val) -> ((Integer) val).byteValue();
            case SMALLINT:
                return (val) -> val instanceof Integer ? ((Integer) val).shortValue() : val;
            case DECIMAL:
                int precision = ((DecimalType) type).getPrecision();
                int scale = ((DecimalType) type).getScale();
                return (val) -> val instanceof BigInteger ? DecimalData.fromBigDecimal(new BigDecimal((BigInteger) val, 0), precision, scale) : DecimalData.fromBigDecimal((BigDecimal) val, precision, scale);
            case DATE:
                return (val) -> (int) ((Date) val).toLocalDate().toEpochDay();
            case TIME_WITHOUT_TIME_ZONE:
                return (val) -> (int) (((Time) val).toLocalTime().toNanoOfDay() / 1000000L);
            case TIMESTAMP_WITH_TIME_ZONE:
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return (val) -> TimestampData.fromTimestamp((Timestamp) val);
            case CHAR:
            case VARCHAR:
                return (val) -> StringData.fromString((String) val);
            case ARRAY:
            case ROW:
            case MAP:
            case MULTISET:
            case RAW:
            default:
                throw new UnsupportedOperationException("Unsupported type:" + type);
        }
    }

    protected ClickHouseRowConverter.SerializationConverter createToClickHouseConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case BOOLEAN:
                return (val, index, statement) -> statement.setBoolean(index + 1, val.getBoolean(index));
            case FLOAT:
                return (val, index, statement) -> statement.setFloat(index + 1, val.getFloat(index));
            case DOUBLE:
                return (val, index, statement) -> statement.setDouble(index + 1, val.getDouble(index));
            case INTERVAL_YEAR_MONTH:
            case INTEGER:
                return (val, index, statement) -> statement.setInt(index + 1, val.getInt(index));
            case INTERVAL_DAY_TIME:
            case BIGINT:
                return (val, index, statement) -> statement.setLong(index + 1, val.getLong(index));
            case TINYINT:
                return (val, index, statement) -> statement.setByte(index + 1, val.getByte(index));
            case SMALLINT:
                return (val, index, statement) -> statement.setShort(index + 1, val.getShort(index));
            case DECIMAL:
                int decimalPrecision = ((DecimalType) type).getPrecision();
                int decimalScale = ((DecimalType) type).getScale();
                return (val, index, statement) -> statement.setBigDecimal(index + 1, val.getDecimal(index, decimalPrecision, decimalScale).toBigDecimal());
            case DATE:
                return (val, index, statement) -> statement.setDate(index + 1, Date.valueOf(LocalDate.ofEpochDay(val.getInt(index))));
            case TIME_WITHOUT_TIME_ZONE:
                return (val, index, statement) -> statement.setTime(index + 1, Time.valueOf(LocalTime.ofNanoOfDay((long) val.getInt(index) * 1000000L)));
            case TIMESTAMP_WITH_TIME_ZONE:
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                int timestampPrecision = ((TimestampType) type).getPrecision();
                return (val, index, statement) -> statement.setTimestamp(index + 1, val.getTimestamp(index, timestampPrecision).toTimestamp());
            case CHAR:
            case VARCHAR:
                return (val, index, statement) -> statement.setString(index + 1, val.getString(index).toString());
            case BINARY:
            case VARBINARY:
                return (val, index, statement) -> statement.setBytes(index + 1, val.getBinary(index));
            case ARRAY:
            case ROW:
            case MAP:
            case MULTISET:
            case RAW:
            default:
                throw new UnsupportedOperationException("Unsupported type:" + type);
        }
    }

    @FunctionalInterface
    interface SerializationConverter extends Serializable {
        void serialize(RowData var1, int var2, PreparedStatement var3) throws SQLException;
    }

    @FunctionalInterface
    interface DeserializationConverter extends Serializable {
        Object deserialize(Object var1) throws SQLException;
    }
}