/*
 * Autopsy Forensic Browser
 *
 * Copyright 2019-2019 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.commandlineingest;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import org.netbeans.spi.sendopts.OptionProcessor;
import org.openide.LifecycleManager;
import org.openide.util.Lookup;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.CaseActionException;
import org.sleuthkit.autopsy.casemodule.CaseDetails;
import org.sleuthkit.autopsy.casemodule.CaseMetadata;
import static org.sleuthkit.autopsy.casemodule.CaseMetadata.getFileExtension;
import org.sleuthkit.autopsy.core.RuntimeProperties;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataSourceProcessorCallback;
import static org.sleuthkit.autopsy.corecomponentinterfaces.DataSourceProcessorCallback.DataSourceProcessorResult.CRITICAL_ERRORS;
import static org.sleuthkit.autopsy.corecomponentinterfaces.DataSourceProcessorCallback.DataSourceProcessorResult.NO_ERRORS;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataSourceProcessorProgressMonitor;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.TimeStampUtils;
import org.sleuthkit.autopsy.datasourceprocessors.AutoIngestDataSourceProcessor;
import org.sleuthkit.autopsy.datasourceprocessors.AutoIngestDataSource;
import org.sleuthkit.autopsy.datasourceprocessors.AddDataSourceCallback;
import org.sleuthkit.autopsy.datasourceprocessors.DataSourceProcessorUtility;
import org.sleuthkit.autopsy.events.AutopsyEvent;
import org.sleuthkit.autopsy.ingest.IngestJob;
import org.sleuthkit.autopsy.ingest.IngestJobSettings;
import org.sleuthkit.autopsy.ingest.IngestJobStartResult;
import org.sleuthkit.autopsy.ingest.IngestManager;
import org.sleuthkit.autopsy.ingest.IngestModuleError;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * Allows Autopsy to be invoked with a command line arguments. Causes Autopsy to
 * create a case, add a specified data source, run ingest on that data source,
 * produce a CASE/UCO report and exit.
 */
public class CommandLineIngestManager {

    private static final Logger LOGGER = Logger.getLogger(CommandLineIngestManager.class.getName());
    private Case caseForJob = null;
    private AutoIngestDataSource dataSource = null;
    private static final String LOG_DIR_NAME = "Command Output";

    public CommandLineIngestManager() {
    }

    public void start() {
        new Thread(new JobProcessingTask()).start();
    }

    public void stop() {
        try {
            // close current case if there is one open
            Case.closeCurrentCase();
        } catch (CaseActionException ex) {
            LOGGER.log(Level.WARNING, "Unable to close the case while shutting down command line ingest manager", ex); //NON-NLS
        }

        // shut down Autopsy
        LifecycleManager.getDefault().exit();
    }

    private final class JobProcessingTask implements Runnable {

        private final Object ingestLock;

        private JobProcessingTask() {
            ingestLock = new Object();
            try {
                RuntimeProperties.setRunningWithGUI(false);
                LOGGER.log(Level.INFO, "Set running with desktop GUI runtime property to false");
            } catch (RuntimeProperties.RuntimePropertiesException ex) {
                LOGGER.log(Level.SEVERE, "Failed to set running with desktop GUI runtime property to false", ex);
            }
        }

        /**
         * Requests the list of command line commands from command line options
         * processor and executes the commands one by one.
         */
        public void run() {
            LOGGER.log(Level.INFO, "Job processing task started");

            try {
                // read command line inputs
                LOGGER.log(Level.INFO, "Autopsy is running from command line"); //NON-NLS
                List<CommandLineCommand> commands = null;

                // first look up all OptionProcessors and get input data from CommandLineOptionProcessor
                Collection<? extends OptionProcessor> optionProcessors = Lookup.getDefault().lookupAll(OptionProcessor.class);
                Iterator<? extends OptionProcessor> optionsIterator = optionProcessors.iterator();
                while (optionsIterator.hasNext()) {
                    // find CommandLineOptionProcessor
                    OptionProcessor processor = optionsIterator.next();
                    if (processor instanceof CommandLineOptionProcessor) {
                        // check if we are running from command line                       
                        commands = ((CommandLineOptionProcessor) processor).getCommands();
                    }
                }

                if (commands == null || commands.isEmpty()) {
                    LOGGER.log(Level.SEVERE, "No command line commands specified");
                    System.err.println("No command line commands specified");
                    return;
                }

                try {
                    // Commands are already stored in order in which they should be executed                
                    for (CommandLineCommand command : commands) {
                        CommandLineCommand.CommandType type = command.getType();
                        switch (type) {
                            case CREATE_CASE:
                                try {
                                    LOGGER.log(Level.INFO, "Processing 'Create Case' command");
                                    System.out.println("Processing 'Create Case' command");
                                    openCase(command);

                                    String outputDirPath = getOutputDirPath(caseForJob);
                                    OutputGenerator.saveCreateCaseOutput(caseForJob, outputDirPath);
                                } catch (CaseActionException ex) {
                                    String baseCaseName = command.getInputs().get(CommandLineCommand.InputType.CASE_NAME.name());
                                    LOGGER.log(Level.SEVERE, "Error creating or opening case " + baseCaseName, ex);
                                    System.err.println("Error creating or opening case " + baseCaseName);
                                    // Do not process any other commands
                                    return;
                                }
                                break;
                            case ADD_DATA_SOURCE:
                                try {
                                    LOGGER.log(Level.INFO, "Processing 'Add Data Source' command");
                                    System.out.println("Processing 'Add Data Source' command");
                                    Map<String, String> inputs = command.getInputs();

                                    // open the case, if it hasn't been already opened by CREATE_CASE command
                                    if (caseForJob == null) {
                                        String caseDirPath = inputs.get(CommandLineCommand.InputType.CASE_FOLDER_PATH.name());
                                        openCase(caseDirPath);
                                    }

                                    String dataSourcePath = inputs.get(CommandLineCommand.InputType.DATA_SOURCE_PATH.name());
                                    dataSource = new AutoIngestDataSource("", Paths.get(dataSourcePath));
                                    runDataSourceProcessor(caseForJob, dataSource);

                                    String outputDirPath = getOutputDirPath(caseForJob);
                                    OutputGenerator.saveAddDataSourceOutput(caseForJob, dataSource, outputDirPath);
                                } catch (InterruptedException | AutoIngestDataSourceProcessor.AutoIngestDataSourceProcessorException | CaseActionException ex) {
                                    String dataSourcePath = command.getInputs().get(CommandLineCommand.InputType.DATA_SOURCE_PATH.name());
                                    LOGGER.log(Level.SEVERE, "Error adding data source " + dataSourcePath, ex);
                                    System.err.println("Error adding data source " + dataSourcePath);
                                    // Do not process any other commands
                                    return;
                                }
                                break;
                            case RUN_INGEST:
                                try {
                                    LOGGER.log(Level.INFO, "Processing 'Run Ingest' command");
                                    System.out.println("Processing 'Run Ingest' command");
                                    Map<String, String> inputs = command.getInputs();

                                    // open the case, if it hasn't been already opened by CREATE_CASE or ADD_DATA_SOURCE commands
                                    if (caseForJob == null) {
                                        String caseDirPath = inputs.get(CommandLineCommand.InputType.CASE_FOLDER_PATH.name());
                                        openCase(caseDirPath);
                                    }

                                    // populate the AutoIngestDataSource structure, if that hasn't been done by ADD_DATA_SOURCE command
                                    if (dataSource == null) {

                                        String dataSourceId = inputs.get(CommandLineCommand.InputType.DATA_SOURCE_ID.name());
                                        Long dataSourceObjId = Long.valueOf(dataSourceId);

                                        // get Content object for the data source
                                        Content content = null;
                                        try {
                                            content = Case.getCurrentCaseThrows().getSleuthkitCase().getContentById(dataSourceObjId);
                                        } catch (TskCoreException ex) {
                                            LOGGER.log(Level.SEVERE, "Exception while trying to find data source with object ID " + dataSourceId, ex);
                                            System.err.println("Exception while trying to find data source with object ID " + dataSourceId);
                                            // Do not process any other commands
                                            return;
                                        }

                                        if (content == null) {
                                            LOGGER.log(Level.SEVERE, "Unable to find data source with object ID {0}", dataSourceId);
                                            System.out.println("Unable to find data source with object ID " + dataSourceId);
                                            // Do not process any other commands
                                            return;
                                        }

                                        // populate the AutoIngestDataSource structure
                                        dataSource = new AutoIngestDataSource("", Paths.get(content.getName()));
                                        List<Content> contentList = Arrays.asList(new Content[]{content});
                                        List<String> errorList = new ArrayList<>();
                                        dataSource.setDataSourceProcessorOutput(NO_ERRORS, errorList, contentList);
                                    }

                                    // run ingest
                                    analyze(dataSource);
                                } catch (InterruptedException | CaseActionException ex) {
                                    String dataSourcePath = command.getInputs().get(CommandLineCommand.InputType.DATA_SOURCE_PATH.name());
                                    LOGGER.log(Level.SEVERE, "Error running ingest on data source " + dataSourcePath, ex);
                                    System.err.println("Error running ingest on data source " + dataSourcePath);
                                    // Do not process any other commands
                                    return;
                                }
                                break;

                            case LIST_ALL_DATA_SOURCES:
                                try {
                                    LOGGER.log(Level.INFO, "Processing 'List All Data Sources' command");
                                    System.out.println("Processing 'List All Data Sources' command");
                                    Map<String, String> inputs = command.getInputs();

                                    // open the case, if it hasn't been already opened by previous command
                                    if (caseForJob == null) {
                                        String caseDirPath = inputs.get(CommandLineCommand.InputType.CASE_FOLDER_PATH.name());
                                        openCase(caseDirPath);
                                    }

                                    String outputDirPath = getOutputDirPath(caseForJob);
                                    OutputGenerator.listAllDataSources(caseForJob, outputDirPath);
                                } catch (CaseActionException ex) {
                                    String caseDirPath = command.getInputs().get(CommandLineCommand.InputType.CASE_FOLDER_PATH.name());
                                    LOGGER.log(Level.SEVERE, "Error opening case in case directory: " + caseDirPath, ex);
                                    System.err.println("Error opening case in case directory: " + caseDirPath);
                                    // Do not process any other commands
                                    return;
                                }
                                break;
                            default:
                                break;
                        }
                    }
                } catch (Throwable ex) {
                    /*
                    * Unexpected runtime exceptions firewall. This task is designed to
                    * be able to be run in an executor service thread pool without
                    * calling get() on the task's Future<Void>, so this ensures that
                    * such errors get logged.
                     */
                    LOGGER.log(Level.SEVERE, "Unexpected error", ex);
                    System.err.println("Unexpected error. Exiting...");

                } finally {
                    try {
                        Case.closeCurrentCase();
                    } catch (CaseActionException ex) {
                        LOGGER.log(Level.WARNING, "Exception while closing case", ex);
                        System.err.println("Exception while closing case");
                    }
                }

            } finally {
                LOGGER.log(Level.INFO, "Job processing task finished");
                System.out.println("Job processing task finished");

                // shut down Autopsy
                stop();
            }
        }

        /**
         * Creates a new case using arguments passed in from command line
         * CREATE_CASE command.
         *
         * @param command command line CREATE_CASE command
         * @throws CaseActionException
         */
        private void openCase(CommandLineCommand command) throws CaseActionException {

            Map<String, String> inputs = command.getInputs();

            String baseCaseName = inputs.get(CommandLineCommand.InputType.CASE_NAME.name());
            String rootOutputDirectory = inputs.get(CommandLineCommand.InputType.CASES_BASE_DIR_PATH.name());
            LOGGER.log(Level.INFO, "Opening case {0} in directory {1}", new Object[]{baseCaseName, rootOutputDirectory});

            Path caseDirectoryPath = findCaseDirectory(Paths.get(rootOutputDirectory), baseCaseName);
            if (null != caseDirectoryPath) {
                // found an existing case directory for same case name. the input case name must be unique. Exit.
                LOGGER.log(Level.SEVERE, "Case {0} already exists. Case name must be unique. Exiting", baseCaseName);
                throw new CaseActionException("Case " + baseCaseName + " already exists. Case name must be unique. Exiting");
            } else {
                caseDirectoryPath = createCaseFolderPath(Paths.get(rootOutputDirectory), baseCaseName);

                // Create the case directory
                Case.createCaseDirectory(caseDirectoryPath.toString(), Case.CaseType.SINGLE_USER_CASE);

                CaseDetails caseDetails = new CaseDetails(baseCaseName);
                Case.createAsCurrentCase(Case.CaseType.SINGLE_USER_CASE, caseDirectoryPath.toString(), caseDetails);
            }

            caseForJob = Case.getCurrentCase();
            LOGGER.log(Level.INFO, "Opened case {0}", caseForJob.getName());
        }

        /**
         * Opens existing case.
         *
         * @param caseFolderPath full path to case directory
         * @throws CaseActionException
         */
        private void openCase(String caseFolderPath) throws CaseActionException {

            LOGGER.log(Level.INFO, "Opening case in directory {0}", caseFolderPath);

            String metadataFilePath = findAutFile(caseFolderPath);
            Case.openAsCurrentCase(metadataFilePath);

            caseForJob = Case.getCurrentCase();
            LOGGER.log(Level.INFO, "Opened case {0}", caseForJob.getName());
        }

        /**
         * Finds the path to the .aut file for the specified case directory.
         *
         * @param caseDirectory the directory to check for a .aut file
         *
         * @return the path to the first .aut file found in the directory
         *
         * @throws CaseActionException if there was an issue finding a .aut file
         */
        private String findAutFile(String caseDirectory) throws CaseActionException {
            File caseFolder = Paths.get(caseDirectory).toFile();
            if (caseFolder.exists()) {
                /*
                 * Search for '*.aut' files.
                 */
                File[] fileArray = caseFolder.listFiles();
                if (fileArray == null) {
                    throw new CaseActionException("No files found in case directory");
                }
                String autFilePath = null;
                for (File file : fileArray) {
                    String name = file.getName().toLowerCase();
                    if (autFilePath == null && name.endsWith(getFileExtension())) {
                        return file.getAbsolutePath();
                    }
                }
                throw new CaseActionException("No .aut files found in case directory");
            }
            throw new CaseActionException("Case directory was not found");
        }

        /**
         * Passes the data source for the current job through a data source
         * processor that adds it to the case database.
         *
         * @param caseForJob The case
         * @param dataSource The data source.
         *
         * @throws
         * AutoIngestDataSourceProcessor.AutoIngestDataSourceProcessorException
         * if there was a DSP processing error
         *
         * @throws InterruptedException if the thread running the job processing
         * task is interrupted while blocked, i.e., if auto ingest is shutting
         * down.
         */
        private void runDataSourceProcessor(Case caseForJob, AutoIngestDataSource dataSource) throws InterruptedException, AutoIngestDataSourceProcessor.AutoIngestDataSourceProcessorException {

            LOGGER.log(Level.INFO, "Adding data source {0} ", dataSource.getPath().toString());

            // Get an ordered list of data source processors to try
            List<AutoIngestDataSourceProcessor> validDataSourceProcessors;
            try {
                validDataSourceProcessors = DataSourceProcessorUtility.getOrderedListOfDataSourceProcessors(dataSource.getPath());
            } catch (AutoIngestDataSourceProcessor.AutoIngestDataSourceProcessorException ex) {
                LOGGER.log(Level.SEVERE, "Exception while determining best data source processor for {0}", dataSource.getPath());
                // rethrow the exception. 
                throw ex;
            }

            // did we find a data source processor that can process the data source
            if (validDataSourceProcessors.isEmpty()) {
                // This should never happen. We should add all unsupported data sources as logical files.
                LOGGER.log(Level.SEVERE, "Unsupported data source {0}", dataSource.getPath());  // NON-NLS
                return;
            }

            DataSourceProcessorProgressMonitor progressMonitor = new DoNothingDSPProgressMonitor();
            synchronized (ingestLock) {
                // Try each DSP in decreasing order of confidence
                for (AutoIngestDataSourceProcessor selectedProcessor : validDataSourceProcessors) {
                    UUID taskId = UUID.randomUUID();
                    caseForJob.notifyAddingDataSource(taskId);
                    DataSourceProcessorCallback callBack = new AddDataSourceCallback(caseForJob, dataSource, taskId, ingestLock);
                    caseForJob.notifyAddingDataSource(taskId);
                    LOGGER.log(Level.INFO, "Identified data source type for {0} as {1}", new Object[]{dataSource.getPath(), selectedProcessor.getDataSourceType()});
                    selectedProcessor.process(dataSource.getDeviceId(), dataSource.getPath(), progressMonitor, callBack);
                    ingestLock.wait();

                    // at this point we got the content object(s) from the current DSP.
                    // check whether the data source was processed successfully
                    if ((dataSource.getResultDataSourceProcessorResultCode() == CRITICAL_ERRORS)
                            || dataSource.getContent().isEmpty()) {
                        // move onto the the next DSP that can process this data source
                        logDataSourceProcessorResult(dataSource);
                        continue;
                    }

                    logDataSourceProcessorResult(dataSource);
                    return;
                }
                // If we get to this point, none of the processors were successful
                LOGGER.log(Level.SEVERE, "All data source processors failed to process {0}", dataSource.getPath());
                // Throw an exception. It will get caught & handled upstream and will result in AIM auto-pause.
                throw new AutoIngestDataSourceProcessor.AutoIngestDataSourceProcessorException("Failed to process " + dataSource.getPath() + " with all data source processors");
            }
        }

        /**
         * Logs the results of running a data source processor on the data
         * source for the current job.
         *
         * @param dataSource The data source.
         */
        private void logDataSourceProcessorResult(AutoIngestDataSource dataSource) {

            DataSourceProcessorCallback.DataSourceProcessorResult resultCode = dataSource.getResultDataSourceProcessorResultCode();
            if (null != resultCode) {
                switch (resultCode) {
                    case NO_ERRORS:
                        LOGGER.log(Level.INFO, "Added data source to case");
                        if (dataSource.getContent().isEmpty()) {
                            LOGGER.log(Level.SEVERE, "Data source failed to produce content");
                        }
                        break;

                    case NONCRITICAL_ERRORS:
                        for (String errorMessage : dataSource.getDataSourceProcessorErrorMessages()) {
                            LOGGER.log(Level.WARNING, "Non-critical error running data source processor for {0}: {1}", new Object[]{dataSource.getPath(), errorMessage});
                        }
                        LOGGER.log(Level.INFO, "Added data source to case");
                        if (dataSource.getContent().isEmpty()) {
                            LOGGER.log(Level.SEVERE, "Data source failed to produce content");
                        }
                        break;

                    case CRITICAL_ERRORS:
                        for (String errorMessage : dataSource.getDataSourceProcessorErrorMessages()) {
                            LOGGER.log(Level.SEVERE, "Critical error running data source processor for {0}: {1}", new Object[]{dataSource.getPath(), errorMessage});
                        }
                        LOGGER.log(Level.SEVERE, "Failed to add data source to case");
                        break;
                }
            } else {
                LOGGER.log(Level.WARNING, "No result code for data source processor for {0}", dataSource.getPath());
            }
        }

        /**
         * Analyzes the data source content returned by the data source
         * processor using the configured set of data source level and file
         * level analysis modules.
         *
         * @param dataSource The data source to analyze.
         *
         * @throws AnalysisStartupException if there is an error analyzing the
         * data source.
         * @throws InterruptedException if the thread running the job processing
         * task is interrupted while blocked, i.e., if auto ingest is shutting
         * down.
         */
        private void analyze(AutoIngestDataSource dataSource) throws AnalysisStartupException, InterruptedException {

            LOGGER.log(Level.INFO, "Starting ingest modules analysis for {0} ", dataSource.getPath());
            IngestJobEventListener ingestJobEventListener = new IngestJobEventListener();
            IngestManager.getInstance().addIngestJobEventListener(ingestJobEventListener);
            try {
                synchronized (ingestLock) {
                    IngestJobSettings ingestJobSettings = new IngestJobSettings(UserPreferences.getCommandLineModeIngestModuleContextString());
                    List<String> settingsWarnings = ingestJobSettings.getWarnings();
                    if (settingsWarnings.isEmpty()) {
                        IngestJobStartResult ingestJobStartResult = IngestManager.getInstance().beginIngestJob(dataSource.getContent(), ingestJobSettings);
                        IngestJob ingestJob = ingestJobStartResult.getJob();
                        if (null != ingestJob) {
                            /*
                             * Block until notified by the ingest job event
                             * listener or until interrupted because auto ingest
                             * is shutting down.
                             */
                            ingestLock.wait();
                            LOGGER.log(Level.INFO, "Finished ingest modules analysis for {0} ", dataSource.getPath());
                            IngestJob.ProgressSnapshot jobSnapshot = ingestJob.getSnapshot();
                            for (IngestJob.ProgressSnapshot.DataSourceProcessingSnapshot snapshot : jobSnapshot.getDataSourceSnapshots()) {
                                if (!snapshot.isCancelled()) {
                                    List<String> cancelledModules = snapshot.getCancelledDataSourceIngestModules();
                                    if (!cancelledModules.isEmpty()) {
                                        LOGGER.log(Level.WARNING, String.format("Ingest module(s) cancelled for %s", dataSource.getPath()));
                                        for (String module : snapshot.getCancelledDataSourceIngestModules()) {
                                            LOGGER.log(Level.WARNING, String.format("%s ingest module cancelled for %s", module, dataSource.getPath()));
                                        }
                                    }
                                    LOGGER.log(Level.INFO, "Analysis of data source completed");
                                } else {
                                    LOGGER.log(Level.WARNING, "Analysis of data source cancelled");
                                    IngestJob.CancellationReason cancellationReason = snapshot.getCancellationReason();
                                    if (IngestJob.CancellationReason.NOT_CANCELLED != cancellationReason && IngestJob.CancellationReason.USER_CANCELLED != cancellationReason) {
                                        throw new AnalysisStartupException(String.format("Analysis cancelled due to %s for %s", cancellationReason.getDisplayName(), dataSource.getPath()));
                                    }
                                }
                            }
                        } else if (!ingestJobStartResult.getModuleErrors().isEmpty()) {
                            for (IngestModuleError error : ingestJobStartResult.getModuleErrors()) {
                                LOGGER.log(Level.SEVERE, String.format("%s ingest module startup error for %s", error.getModuleDisplayName(), dataSource.getPath()), error.getThrowable());
                            }
                            LOGGER.log(Level.SEVERE, "Failed to analyze data source due to ingest job startup error");
                            throw new AnalysisStartupException(String.format("Error(s) during ingest module startup for %s", dataSource.getPath()));
                        } else {
                            LOGGER.log(Level.SEVERE, String.format("Ingest manager ingest job start error for %s", dataSource.getPath()), ingestJobStartResult.getStartupException());
                            throw new AnalysisStartupException("Ingest manager error starting job", ingestJobStartResult.getStartupException());
                        }
                    } else {
                        for (String warning : settingsWarnings) {
                            LOGGER.log(Level.SEVERE, "Ingest job settings error for {0}: {1}", new Object[]{dataSource.getPath(), warning});
                        }
                        LOGGER.log(Level.SEVERE, "Failed to analyze data source due to settings errors");
                        throw new AnalysisStartupException("Error(s) in ingest job settings");
                    }
                }
            } finally {
                IngestManager.getInstance().removeIngestJobEventListener(ingestJobEventListener);
            }
        }

        /**
         * Creates a case folder path. Does not create the folder described by
         * the path.
         *
         * @param caseFoldersPath The root case folders path.
         * @param caseName The name of the case.
         *
         * @return A case folder path with a time stamp suffix.
         */
        private Path createCaseFolderPath(Path caseFoldersPath, String caseName) {
            String folderName = caseName + "_" + TimeStampUtils.createTimeStamp();
            return Paths.get(caseFoldersPath.toString(), folderName);
        }

        /**
         * Searches a given folder for the most recently modified case folder
         * for a case.
         *
         * @param folderToSearch The folder to be searched.
         * @param caseName The name of the case for which a case folder is to be
         * found.
         *
         * @return The path of the case folder, or null if it is not found.
         */
        private Path findCaseDirectory(Path folderToSearch, String caseName) {
            File searchFolder = new File(folderToSearch.toString());
            if (!searchFolder.isDirectory()) {
                return null;
            }
            Path caseFolderPath = null;
            String[] candidateFolders = searchFolder.list(new CaseFolderFilter(caseName));
            long mostRecentModified = 0;
            for (String candidateFolder : candidateFolders) {
                File file = new File(candidateFolder);
                if (file.lastModified() >= mostRecentModified) {
                    mostRecentModified = file.lastModified();
                    caseFolderPath = Paths.get(folderToSearch.toString(), file.getPath());
                }
            }
            return caseFolderPath;
        }

        /**
         * Returns full path to directory where command outputs should be saved.
         *
         * @param caseForJob Case object
         * @return Full path to directory where command outputs should be saved
         */
        private String getOutputDirPath(Case caseForJob) {
            return caseForJob.getCaseDirectory() + File.separator + LOG_DIR_NAME;
        }

        /**
         * An ingest job event listener that allows the job processing task to
         * block until the analysis of a data source by the data source level
         * and file level ingest modules is completed.
         * <p>
         * Note that the ingest job can spawn "child" ingest jobs (e.g., if an
         * embedded virtual machine is found), so the job processing task must
         * remain blocked until ingest is no longer running.
         */
        private class IngestJobEventListener implements PropertyChangeListener {

            /**
             * Listens for local ingest job completed or cancelled events and
             * notifies the job processing thread when such an event occurs and
             * there are no "child" ingest jobs running.
             *
             * @param event
             */
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                if (AutopsyEvent.SourceType.LOCAL == ((AutopsyEvent) event).getSourceType()) {
                    String eventType = event.getPropertyName();
                    if (eventType.equals(IngestManager.IngestJobEvent.COMPLETED.toString()) || eventType.equals(IngestManager.IngestJobEvent.CANCELLED.toString())) {
                        synchronized (ingestLock) {
                            ingestLock.notify();
                        }
                    }
                }
            }
        };

        /**
         * A data source processor progress monitor does nothing. There is
         * currently no mechanism for showing or recording data source processor
         * progress during an ingest job.
         */
        private class DoNothingDSPProgressMonitor implements DataSourceProcessorProgressMonitor {

            /**
             * Does nothing.
             *
             * @param indeterminate
             */
            @Override
            public void setIndeterminate(final boolean indeterminate) {
            }

            /**
             * Does nothing.
             *
             * @param progress
             */
            @Override
            public void setProgress(final int progress) {
            }

            /**
             * Does nothing.
             *
             * @param text
             */
            @Override
            public void setProgressText(final String text) {
            }
        }

        /**
         * Exception type thrown when there is a problem analyzing a data source
         * with data source level and file level ingest modules for an ingest
         * job.
         */
        private final class AnalysisStartupException extends Exception {

            private static final long serialVersionUID = 1L;

            private AnalysisStartupException(String message) {
                super(message);
            }

            private AnalysisStartupException(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }

    private static class CaseFolderFilter implements FilenameFilter {

        private final String caseName;
        private final static String CASE_METADATA_EXT = CaseMetadata.getFileExtension();

        CaseFolderFilter(String caseName) {
            this.caseName = caseName;
        }

        @Override
        public boolean accept(File folder, String fileName) {
            File file = new File(folder, fileName);
            if (fileName.length() > TimeStampUtils.getTimeStampLength() && file.isDirectory()) {
                if (TimeStampUtils.endsWithTimeStamp(fileName)) {
                    if (null != caseName) {
                        String fileNamePrefix = fileName.substring(0, fileName.length() - TimeStampUtils.getTimeStampLength());
                        if (fileNamePrefix.equals(caseName)) {
                            return hasCaseMetadataFile(file);
                        }
                    } else {
                        return hasCaseMetadataFile(file);
                    }
                }
            }
            return false;
        }

        /**
         * Determines whether or not there is a case metadata file in a given
         * folder.
         *
         * @param folder The file object representing the folder to search.
         *
         * @return True or false.
         */
        private static boolean hasCaseMetadataFile(File folder) {
            for (File file : folder.listFiles()) {
                if (file.getName().toLowerCase().endsWith(CASE_METADATA_EXT) && file.isFile()) {
                    return true;
                }
            }
            return false;
        }
    }

}
