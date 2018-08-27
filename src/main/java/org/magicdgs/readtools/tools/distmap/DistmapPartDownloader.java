/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Daniel Gomez-Sanchez
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

package org.magicdgs.readtools.tools.distmap;

import org.magicdgs.readtools.cmd.RTStandardArguments;
import org.magicdgs.readtools.cmd.argumentcollections.RTOutputArgumentCollection;

import avro.shaded.com.google.common.collect.Lists;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMProgramRecord;
import htsjdk.samtools.SAMTag;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.cram.ref.CRAMReferenceSource;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.util.IOUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.barclay.argparser.Advanced;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.hellbender.engine.ProgressMeter;
import org.broadinstitute.hellbender.engine.ReadsDataSource;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.GATKReadWriter;
import org.broadinstitute.hellbender.utils.read.ReadConstants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Engine for download part files from DistMap.
 *
 * <p>Note: if used within a tool it should be considered as an {@link ArgumentCollection} to allow
 * the user to pass the parameters.
 *
 * @author Daniel Gomez-Sanchez (magicDGS)
 */
final class DistmapPartDownloader {

    private static final String PG_TAG = SAMTag.PG.name();

    protected final Logger logger = LogManager.getLogger(this.getClass());

    // For the progress meter in the GATKTool
    @Argument(fullName = RTStandardArguments.SECONDS_BETWEEN_PROGRESS_UPDATES_NAME, shortName = RTStandardArguments.SECONDS_BETWEEN_PROGRESS_UPDATES_NAME, doc = "Output traversal statistics every time this many seconds elapse.", optional = true, common = true)
    private double secondsBetweenProgressUpdates = ProgressMeter.DEFAULT_SECONDS_BETWEEN_UPDATES;

    @Argument(fullName = RTStandardArguments.REFERENCE_LONG_NAME, shortName = RTStandardArguments.REFERENCE_SHORT_NAME, doc = "Reference sequence file. Required for CRAM input/output.", optional = true, common = true)
    private String referenceFile = null;

    @ArgumentCollection
    private RTOutputArgumentCollection outputArgumentCollection =
            RTOutputArgumentCollection.defaultOutput();

    @Argument(fullName = RTStandardArguments.SORT_ORDER_LONG_NAME, shortName = RTStandardArguments.SORT_ORDER_SHORT_NAME, doc = "Sort order of output file", optional = true, common = true)
    private SAMFileHeader.SortOrder sortOrder = SAMFileHeader.SortOrder.coordinate;

    @Advanced
    @Argument(fullName = "numberOfParts", doc = "Number of part files to download, merge and pre-sort at the same time. Reduce this number if you have memory errors.", optional = true, minValue = 1)
    private int numberOfParts = 100;

    @Advanced
    @Argument(fullName = "noRemoveTaskProgramGroup", optional = true,
            doc = "Do not remove the @PG lines generated by every task in the MapReduce Distmap run (default is remove completely). "
                    + "Note: it might merge @PG tags if they are completely equal.")
    private boolean noRemoveTaskProgramGroup = false;

    @Argument(fullName = RTStandardArguments.READ_VALIDATION_STRINGENCY_LONG_NAME, shortName = RTStandardArguments.READ_VALIDATION_STRINGENCY_SHORT_NAME,
            doc = RTStandardArguments.READ_VALIDATION_STRINGENCY_DOC,
            common = true, optional = true)
    private ValidationStringency readValidationStringency =
            ReadConstants.DEFAULT_READ_VALIDATION_STRINGENCY;

    // SamReaderFactory constructed on demand with the parameters
    private SamReaderFactory factory = null;

    private SamReaderFactory getSamReaderFactory() {
        if (factory == null) {
            // TODO - a method for set a Path reference sequence should be included in HTSJDK
            // TODO - this should be changed to the Path setter in HTSJDK (https://github.com/samtools/htsjdk/pull/1005)
            final CRAMReferenceSource source = new ReferenceSource(getReferencePath());
            factory = SamReaderFactory.makeDefault()
                    .referenceSource(source)
                    .validationStringency(readValidationStringency);
        }
        return factory;
    }

    // reference path constructed on demand
    private Path referencePath = null;

    private Path getReferencePath() {
        if (referencePath == null && referenceFile != null) {
            referencePath = IOUtils.getPath(referenceFile);
        }
        return referencePath;
    }

    // helper method to construct always in the same way a progress meter
    private ProgressMeter buildProgressMeter() {
        final ProgressMeter progress = new ProgressMeter(secondsBetweenProgressUpdates);
        // we are only getting reads
        progress.setRecordLabel("reads");
        return progress;
    }

    /**
     * Download the part files in the list.
     *
     * @param partFiles     part files to download.
     * @param programRecord function to construct a program record using the header. May return a
     *                      {@code null} program record.
     */
    public void downloadParts(final List<Path> partFiles,
            final Function<SAMFileHeader, SAMProgramRecord> programRecord) {
        Utils.nonEmpty(partFiles);
        outputArgumentCollection.validateUserOutput();

        // for the final merging
        final ReadsDataSource toMerge;
        final boolean presorted;

        if (partFiles.size() <= numberOfParts) {
            // logging the process and start the progress meter
            logger.info(
                    "Only {} parts found: download will be performed at the same time as merging",
                    partFiles::size);
            toMerge = new ReadsDataSource(partFiles, getSamReaderFactory());
            presorted = isPresorted(toMerge.getHeader());
            logger.debug("Presorted = {}", presorted);
        } else {
            toMerge = downloadBatchesAndPreSort(partFiles);
            presorted = true;
        }

        final SAMFileHeader header = setHeaderOptions(toMerge.getHeader());

        // initialize the progress meter
        logger.info("Merging output");
        final ProgressMeter mergingProgress = buildProgressMeter();
        mergingProgress.start();

        writeReads(toMerge, header,
                outputArgumentCollection.outputWriter(header, () -> programRecord.apply(header),
                        presorted, getReferencePath()),
                mergingProgress);

        toMerge.close();
        // logging the end
        mergingProgress.stop();
        logger.info("Finished download and merging.", partFiles::size);
    }

    /**
     * Returns {@code true} if the parameters suggest that the files are pre-sorted; {@code false}
     * otherwise.
     *
     * @param header header for checking the sort order.
     */
    private boolean isPresorted(final SAMFileHeader header) {
        // if the requested order is unsorted
        return SAMFileHeader.SortOrder.unsorted == sortOrder
                // if the header is the same sort order than the one requested
                || header.getSortOrder() == sortOrder;
    }

    /**
     * Sets the sort order to the one specified in {@link #sortOrder} and remove the @PG lines it
     * {@link #noRemoveTaskProgramGroup} is false.
     *
     * @param header header to update.
     *
     * @return new header (modified accordingly).
     */
    private SAMFileHeader setHeaderOptions(final SAMFileHeader header) {
        // clone the SAMFileHeader
        final SAMFileHeader toReturn = header.clone();
        toReturn.setSortOrder(sortOrder);
        if (noRemoveTaskProgramGroup) {
            logger.warn(
                    "Program Group header lines (@PG) from DistMap multi-part output are maintained. This may contain information for each Map-Reduce task.");
        } else {
            toReturn.setProgramRecords(new ArrayList<>());
        }
        return toReturn;
    }


    /**
     * Write the reads contained in the source into the writer.
     *
     * <p>Note: the writer is closed before returning.
     */
    private void writeReads(final ReadsDataSource reads, final SAMFileHeader header,
            final GATKReadWriter writer,
            final ProgressMeter progressMeter) {
        for (final GATKRead read : reads) {
            // check if the read has a PG tag and if it is included in the header
            final String readPg = read.getAttributeAsString(PG_TAG);
            if (readPg != null && header.getProgramRecord(readPg) == null) {
                // if it isn't, it clears the PG tag
                read.clearAttribute(PG_TAG);
            }
            writer.addRead(read);
            progressMeter.update(read);
        }
        try {
            writer.close();
        } catch (IOException e) {
            throw new GATKException("Unable to close writer");
        }
    }

    /**
     * Download and pre-sort the part files in batches, to the temp directory.
     *
     * @param partFiles all part files to download.
     *
     * @return temporary data source conformed by all the batches.
     *
     * @see #divideIntoBatches(List)
     */
    private ReadsDataSource downloadBatchesAndPreSort(final List<Path> partFiles) {
        // partition the files into batches
        final Map<Path, ReadsDataSource> batches = divideIntoBatches(partFiles);

        // logging the downloading process
        logger.info("Downloading parts in {} batches.", batches::size);
        final ProgressMeter downloadProgress = buildProgressMeter();
        downloadProgress.start();

        // download in batches
        batches.forEach((path, source) -> {
            final String batchName = path.toUri().toString();
            final SAMFileHeader batchHeader = source.getHeader();
            final boolean preSorted = isPresorted(batchHeader);
            logger.debug("Downloading batch: {} (pre-sorted=).", () -> batchName, () -> preSorted);
            writeReads(source, batchHeader,
                    // create based on the batch name, which is BAM and does not require the reference
                    outputArgumentCollection.getWriterFactory()
                            // do not create indexes for the files that are batches
                            .setCreateIndex(false)
                            // overwrite previous batches (this should never happen, but it is a temp folder)
                            .setForceOverwrite(true)
                            .createSAMWriter(batchName, setHeaderOptions(batchHeader), preSorted),
                    downloadProgress);
            source.close();
        });
        downloadProgress.stop();
        logger.info("Finished download.");
        return new ReadsDataSource(new ArrayList<>(batches.keySet()), getSamReaderFactory());
    }

    /**
     * Divide the part files into the batches to download and pre-sort at the same time.
     *
     * @param partFiles all part files to download.
     *
     * @return a map of path to download the parts and the parts that should be used (as reads
     * source).
     *
     * @see #downloadBatchesAndPreSort(List)
     */
    private Map<Path, ReadsDataSource> divideIntoBatches(final List<Path> partFiles) {
        // partition the files into batches
        final List<List<Path>> batches = Lists.partition(partFiles, numberOfParts);
        final Map<Path, ReadsDataSource> toReturn = new LinkedHashMap<>(batches.size());

        // creates a temp file for each in a common temp folder
        final File tempDir = IOUtil.createTempDir(this.toString(), ".batches");
        int i = 0;
        for (final List<Path> parts : batches) {
            // create a temp file and store it in the temp parts
            final Path tempFile =
                    IOUtils.getPath(new File(tempDir, "batch-" + i++ + ".bam").getAbsolutePath());
            logger.debug("Batch file {} will contain {} parts: {}",
                    () -> tempFile.toUri().toString(),
                    () -> parts.size(),
                    () -> parts.stream().map(p -> p.toUri().toString())
                            .collect(Collectors.toList()));
            toReturn.put(tempFile, new ReadsDataSource(parts, getSamReaderFactory()));
        }

        return toReturn;
    }

}
