/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Daniel Gomez-Sanchez
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.magicdgs.readtools.tools.mapped;

import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import org.broadinstitute.hellbender.utils.read.GATKRead;

/**
 * @author Daniel Gomez-Sanchez (magicDGS)
 */
public class RecordOperation {
    /**
     * Check if a record is soft clipped
     *
     * @param read	Record to check
     * @return	true if the read contains a soft clip; false otherwise
     */
    public static boolean isClip(final GATKRead read) {
        // TODO: I don't see any putative problem with this
        return read.getCigarElements().stream().anyMatch(s -> s.getOperator() == CigarOperator.S);
    }

    /**
     * Check if a record contain indels
     *
     * @param read	Record to check
     * @return	true if the read contains an indel; false otherwise
     */
    public static boolean isIndel(final GATKRead read) {
        // TODO: I don't see any putative problem with this
        return read.getCigarElements().stream()
                .anyMatch(s -> s.getOperator() == CigarOperator.I || s.getOperator() == CigarOperator.D);
    }

    /**
     * Check if a record is proper (the mate is mapped in the same reference)
     *
     * @param record	Record to check
     * @return true if is proper; false otherwise
     */
    public static boolean isProper(SAMRecord record) {
        return record.getReferenceIndex() == record.getMateReferenceIndex() && (record.getAlignmentStart() != record.getMateAlignmentStart());
    }

    public static boolean isMateDownstream(SAMRecord record) {
        return isProper(record) && (record.getMateAlignmentStart() > record.getAlignmentStart());
    }
}
