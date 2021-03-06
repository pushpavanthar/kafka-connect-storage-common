/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.storage.partitioner;

import io.confluent.connect.storage.util.DataUtils;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import io.confluent.connect.storage.common.StorageCommonConfig;
import io.confluent.connect.storage.errors.PartitionException;

public class FieldPartitioner<T> extends DefaultPartitioner<T> {
  private static final Logger log = LoggerFactory.getLogger(FieldPartitioner.class);
  private List<String> fieldNames;


  @SuppressWarnings("unchecked")
  @Override
  public void configure(Map<String, Object> config) {
    fieldNames = (List<String>) config.get(PartitionerConfig.PARTITION_FIELD_NAME_CONFIG);
    delim = (String) config.get(StorageCommonConfig.DIRECTORY_DELIM_CONFIG);
  }

  @Override
  public String encodePartition(SinkRecord sinkRecord) {
    Object value = sinkRecord.value();
    StringBuilder builder = new StringBuilder();
    if (value instanceof Struct) {
      final Schema valueSchema = sinkRecord.valueSchema();
      final Struct struct = (Struct) value;
      for (String fieldName : fieldNames) {
        if (builder.length() > 0) {
          builder.append(this.delim);
        }
        Object partitionKey = struct.get(fieldName);
        Type type = valueSchema.field(fieldName).schema().type();
        switch (type) {
          case INT8:
          case INT16:
          case INT32:
          case INT64:
            Number record = (Number) partitionKey;
            builder.append(fieldName + "=" + record.toString());
            break;
          case STRING:
            builder.append(fieldName + "=" + (String) partitionKey);
            break;
          case BOOLEAN:
            boolean booleanRecord = (boolean) partitionKey;
            builder.append(fieldName + "=" + Boolean.toString(booleanRecord));
            break;
          default:
            log.error("Type {} is not supported as a partition key.", type.getName());
            throw new PartitionException("Error encoding partition.");
        }
      }
      return builder.toString();
    } else if (value instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) value;
      for (String fieldName : fieldNames) {
        if (builder.length() > 0) {
          builder.append(this.delim);
        }
        Object fieldValue = "null";
        try {
          fieldValue = DataUtils.getNestedFieldValue(map, fieldName);
        } catch (DataException e) {
          log.warn("{} is unable to parse field - {} from record - {}",
                  this.getClass(), fieldName, sinkRecord.value());
        }
        String[] nestedFieldList = fieldName.split("\\.");
        String partitionName = nestedFieldList[nestedFieldList.length - 1];
        if (fieldValue instanceof Number) {
          Number record = (Number) fieldValue;
          builder.append(partitionName + "=" + record.toString());
        } else if (fieldValue instanceof String) {
          builder.append(partitionName + "=" + (String) fieldValue);
        } else if (fieldValue instanceof Boolean) {
          boolean booleanRecord = (boolean) fieldValue;
          builder.append(partitionName + "=" + Boolean.toString(booleanRecord));
        } else {
          log.error(
                  "Unsupported type '{}' for user-defined timestamp field.", fieldValue.getClass()
          );
          throw new PartitionException(
                  "Error extracting timestamp from record field: " + fieldName
          );
        }
      }
      return builder.toString();
    } else {
      log.error("Value is not Struct type.");
      throw new PartitionException("Error encoding partition.");
    }
  }

  @Override
  public List<T> partitionFields() {
    if (partitionFields == null) {
      partitionFields = newSchemaGenerator(config).newPartitionFields(
          Utils.join(fieldNames, ",")
      );
    }
    return partitionFields;
  }
}
