/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Daniel Gómez-Sánchez
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
 */
package org.magicdgs.readtools.tools.trimming.trimmers;

import org.magicdgs.io.FastqPairedRecord;
import org.magicdgs.readtools.tools.trimming.trimmers.stats.PairEndTrimming;
import org.magicdgs.readtools.tools.trimming.trimmers.stats.TrimStat;

import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.FastqQualityFormat;
import htsjdk.samtools.util.Histogram;

import java.io.File;

/**
 * @author Daniel Gómez-Sánchez
 */
public class TrimmerPaired extends Trimmer {

    /**
     * The metrics for the first pair
     */
    private final TrimStat metricPair1;

    /**
     * The histogram for the first pair
     */
    private final Histogram<Integer> histogramPair1;

    /**
     * The metrics for the second pair
     */
    private final TrimStat metricPair2;

    /**
     * The histogram for the first pair
     */
    private final Histogram<Integer> histogramPair2;

    /**
     * The header for the pair en
     */
    private final PairEndTrimming header;

    TrimmerPaired(final boolean trimQuality, final int qualThreshold, final int minLength,
            final int maxLength, final boolean discardRemainingNs, final boolean no5ptrim) {
        super(trimQuality, qualThreshold, minLength, maxLength, discardRemainingNs, no5ptrim);
        header = new PairEndTrimming();
        metricPair1 = new TrimStat("first");
        metricPair2 = new TrimStat("second");
        histogramPair1 = new Histogram<>("length", "first");
        histogramPair2 = new Histogram<>("length", "second");
    }

    @Override
    public FastqPairedRecord trimFastqPairedRecord(final FastqPairedRecord record,
            final FastqQualityFormat format) {
        final FastqRecord record1 =
                trimFastqRecord(record.getRecord1(), format, metricPair1, histogramPair1);
        final FastqRecord record2 =
                trimFastqRecord(record.getRecord2(), format, metricPair2, histogramPair2);
        final FastqPairedRecord toReturn = new FastqPairedRecord(record1, record2);
        if (toReturn.isComplete()) {
            header.IN_PAIR++;
        } else if (toReturn.containRecords()) {
            header.AS_SINGLE++;
        }
        return toReturn;
    }

    @Override
    public FastqRecord trimFastqRecord(final FastqRecord record, final FastqQualityFormat format) {
        final FastqRecord toReturn = trimFastqRecord(record, format, metricPair1, histogramPair2);
        if (toReturn != null) {
            header.AS_SINGLE++;
        }
        return toReturn;
    }

    @Override
    public void printTrimmerMetrics(final File metricsFile) {
        final MetricsFile<TrimStat, Integer> metrics = new MetricsFile<>();
        metrics.addMetric(metricPair1);
        metrics.addMetric(metricPair2);
        metrics.addHistogram(histogramPair1);
        metrics.addHistogram(histogramPair2);
        metrics.addHeader(header);
        metrics.write(metricsFile);
    }
}