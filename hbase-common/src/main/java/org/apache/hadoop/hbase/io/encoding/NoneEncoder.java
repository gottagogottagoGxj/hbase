/*
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
package org.apache.hadoop.hbase.io.encoding;

import java.io.DataOutputStream;
import java.io.IOException;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.io.WritableUtils;

@InterfaceAudience.Private
public class NoneEncoder {

  private DataOutputStream out;
  private HFileBlockDefaultEncodingContext encodingCtx;

  public NoneEncoder(DataOutputStream out,
      HFileBlockDefaultEncodingContext encodingCtx) {
    this.out = out;
    this.encodingCtx = encodingCtx;
  }

  public int write(Cell cell) throws IOException {
    int klength = KeyValueUtil.keyLength(cell);
    int vlength = cell.getValueLength();

    out.writeInt(klength);
    out.writeInt(vlength);
    CellUtil.writeFlatKey(cell, out);
    out.write(cell.getValueArray(), cell.getValueOffset(), vlength);
    int size = klength + vlength + KeyValue.KEYVALUE_INFRASTRUCTURE_SIZE;
    // Write the additional tag into the stream
    if (encodingCtx.getHFileContext().isIncludesTags()) {
      int tagsLength = cell.getTagsLength();
      out.writeShort(tagsLength);
      if (tagsLength > 0) {
        out.write(cell.getTagsArray(), cell.getTagsOffset(), tagsLength);
      }
      size += tagsLength + KeyValue.TAGS_LENGTH_SIZE;
    }
    if (encodingCtx.getHFileContext().isIncludesMvcc()) {
      WritableUtils.writeVLong(out, cell.getSequenceId());
      size += WritableUtils.getVIntSize(cell.getSequenceId());
    }
    return size;
  }

}
