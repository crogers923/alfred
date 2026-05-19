/*
 * Copyright 2018 CapTech Ventures, Inc.
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

package com.captech.alfred.dataConnections.hadoop;

import com.captech.alfred.Constants;
import com.captech.alfred.dataConnections.DataStoreService;
import com.captech.alfred.exceptions.AppInternalError;
import com.captech.alfred.exceptions.KeyExistsException;
import com.captech.alfred.exceptions.NoDataFound;
import com.captech.alfred.instance.Guid;
import com.captech.alfred.instance.InstanceLog;
import com.captech.alfred.template.Template;
import com.captech.alfred.template.refined.Refined;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!local")
@EnableAutoConfiguration
@EnableConfigurationProperties(HadoopProperties.class)
public class HadoopDatastoreService extends DataStoreService {

    private static final Logger logger = LoggerFactory.getLogger(HadoopDatastoreService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    HadoopProperties properties;

    @Autowired
    private FileSystem fileSystem;

    @Override
    public Template getCurrentMetadata(String key) {
        return getMetadata(properties.getCurrentMd(), key);
    }

    @Override
    public Template getDraftMetadata(String key) {
        return getMetadata(properties.getDraftMd(), key);
    }

    @Override
    public Template getSandboxMetadata(String key) {
        return getMetadata(properties.getCurrentSandbox(), key);
    }

    public Template getMetadata(String path, String key) {
        logger.debug("get metadata: " + key);
        String filename = findData(path, key);
        if (filename != null) {
            return readJson(new Path(path, filename), Template.class);
        }
        return null;
    }

    @Override
    public String findMetaDataByFileName(String filename, String stage) {
        String key = null;
        if (StringUtils.isEmpty(stage) || Constants.FINAL_STAGE.equals(stage)) {
            key = findDataFromLS(list(properties.getCurrentMd()), filename, null);
        }
        if (StringUtils.isEmpty(stage) || Constants.SANDBOX.equals(stage)) {
            key = findDataFromLS(list(properties.getCurrentSandbox()), filename, key);
        }
        return key;
    }

    private String findDataFromLS(List<FileStatus> fileStatuses, String filename, String lastfound) {
        String key = lastfound;
        for (FileStatus status : fileStatuses) {
            if (status.isFile()) {
                String name = status.getPath().getName().split(Constants.OWNER_PREFIX)[0];
                if (matchesPattern(filename, name)) {
                    String tempKey = parseKey(name).get(Constants.TEMPLATE_KEY);
                    if (key == null || tempKey.length() > key.length()) {
                        key = tempKey;
                    }
                }
            }
        }
        return key;
    }

    @Override
    public String updateMetadata(String key, Template input) {
        if (Constants.DRAFT.equalsIgnoreCase(input.getStage())) {
            deleteDraft(key);
        } else {
            deleteMetadata(key);
        }
        return addNewMetadata(input);
    }

    @Override
    public String addNewMetadata(Template metadata) {
        if (!hasRequiredFields(metadata)) {
            return null;
        }
        if (!Constants.DRAFT.equalsIgnoreCase(metadata.getStage())
                && getCurrentMetadata(metadata.getFile().getKey()) != null) {
            throw new KeyExistsException();
        }
        if (Constants.SANDBOX.equalsIgnoreCase(metadata.getStage())
                && getSandboxMetadata(metadata.getFile().getKey()) != null) {
            throw new KeyExistsException();
        }
        if (Constants.REFINED.equalsIgnoreCase(metadata.getStage()) && getRefined(metadata.getFile().getKey()) != null) {
            throw new KeyExistsException();
        }

        String path = properties.getCurrentMd();
        if (Constants.DRAFT.equalsIgnoreCase(metadata.getStage())) {
            path = properties.getDraftMd();
            deleteDraft(metadata.getFile().getKey());
        }
        if (Constants.SANDBOX.equalsIgnoreCase(metadata.getStage())) {
            path = properties.getCurrentSandbox();
        }
        writeJson(path, createKey(metadata), metadata, true, false);
        return metadata.getFile().getKey();
    }

    @Override
    public String deleteMetadata(String key) {
        Template data = getCurrentMetadata(key);
        if (data == null) {
            String returnVal = deleteSandboxMetadata(key);
            if (StringUtils.isEmpty(returnVal) && !deleteDraft(key)) {
                throw new NoDataFound();
            }
            return StringUtils.defaultIfEmpty(returnVal, "draft deleted");
        }
        String formattedVersion = formatVersion(data.getVersion());
        String currentFile = findData(properties.getCurrentMd(), key);
        rename(new Path(properties.getCurrentMd(), currentFile),
                new Path(properties.getVersionedMd(), key + "_" + formattedVersion));
        return properties.getVersionedMd() + key;
    }

    private String deleteSandboxMetadata(String key) {
        Template data = getSandboxMetadata(key);
        if (data == null) {
            return null;
        }
        String formattedVersion = formatVersion(data.getVersion());
        String currentFile = findData(properties.getCurrentSandbox(), key);
        rename(new Path(properties.getCurrentSandbox(), currentFile),
                new Path(properties.getVersionedSandbox(), key + "_" + formattedVersion));
        return properties.getVersionedSandbox() + key;
    }

    @Override
    public List<InstanceLog> getInstanceLog(String guid) {
        List<InstanceLog> logs = new ArrayList<>();
        FileStatus[] fileStatuses = glob(new Path(properties.getLogLocation(), guid + "*"));
        if (fileStatuses.length == 0) {
            throw new NoDataFound();
        }
        for (FileStatus status : fileStatuses) {
            if (status.isFile()) {
                logs.add(readJson(status.getPath(), InstanceLog.class));
            }
        }
        return logs;
    }

    @Override
    public Guid writeInstanceLog(InstanceLog log, String guid) {
        UUID guidUUID;
        if (guid == null) {
            guidUUID = UUID.randomUUID();
            guid = guidUUID.toString();
        } else {
            guidUUID = UUID.fromString(guid);
        }
        log.setGuid(guidUUID);
        if (log.getStage() == null) {
            log.setStage("unknown");
        }
        String name = guid + "_" + log.getStage();
        for (FileStatus status : list(properties.getLogLocation())) {
            if (status.isFile() && stripExtension(status.getPath().getName()).equals(name)) {
                logger.error("Instance Log name: " + name + " already exists. Writing new name");
                name = UUID.randomUUID() + "_" + log.getStage();
            }
        }
        writeJson(properties.getLogLocation(), name, log, false, false);
        return new Guid(guidUUID);
    }

    @Override
    public String addNewRefined(Refined refined) {
        if (!Constants.DRAFT.equalsIgnoreCase(refined.getRefinedDataset().getStage())
                && findCurrentRefined(refined.getRefinedDataset().getFile().getKey()) != null) {
            throw new KeyExistsException();
        }

        String path = properties.getCurrentRefined();
        if (Constants.DRAFT.equalsIgnoreCase(refined.getRefinedDataset().getStage())) {
            path = properties.getDraftRefined();
            deleteRefinedDraft(refined.getRefinedDataset().getFile().getKey());
        }
        writeJson(path, createKey(refined.getRefinedDataset()), refined, true, false);
        return refined.getRefinedDataset().getFile().getKey();
    }

    public Refined getRefined(String path, String key) {
        logger.debug("get refined metadata: " + key);
        String filename = findData(path, key);
        if (filename != null) {
            return readJson(new Path(path, filename), Refined.class);
        }
        return null;
    }

    @Override
    public Refined getRefined(String key) {
        Refined refined = getRefined(properties.getCurrentRefined(), key);
        if (refined != null) {
            return refined;
        }
        refined = getRefined(properties.getDraftRefined(), key);
        if (refined != null) {
            return refined;
        }
        throw new NoDataFound();
    }

    @Override
    public String updateRefined(String key, Refined refined) {
        if (Constants.DRAFT.equalsIgnoreCase(refined.getRefinedDataset().getStage())) {
            deleteRefinedDraft(key);
        } else {
            deleteRefined(key);
        }
        return addNewRefined(refined);
    }

    @Override
    public String deleteRefined(String key) {
        Refined data = getRefined(properties.getCurrentRefined(), key);
        if (data == null) {
            if (!deleteRefinedDraft(key)) {
                throw new NoDataFound();
            }
            return "draft deleted";
        }
        String formattedVersion = formatVersion(data.getRefinedDataset().getVersion());
        String currentFile = findData(properties.getCurrentRefined(), key);
        rename(new Path(properties.getCurrentRefined(), currentFile),
                new Path(properties.getVersionedRefined(), key + "_" + formattedVersion));
        return properties.getVersionedRefined() + key;
    }

    @Override
    public List<String> getAllKeys() {
        ArrayList<String> keys = new ArrayList<>();
        addKeys(keys, properties.getCurrentMd());
        addKeys(keys, properties.getCurrentSandbox());
        return keys;
    }

    @Override
    public long getCount() {
        return count(properties.getCurrentMd())
                + count(properties.getCurrentRefined())
                + count(properties.getCurrentSandbox());
    }

    @Override
    public List<String> getRefinedKeys() {
        ArrayList<String> keys = new ArrayList<>();
        addKeys(keys, properties.getCurrentRefined());
        return keys;
    }

    public void writeSample(String filename, String sampleData) {
        writeText(properties.getFullSampleDir(), filename, sampleData, true, false);
    }

    private void addKeys(List<String> keys, String path) {
        for (FileStatus status : list(path)) {
            if (status.isFile()) {
                keys.add(parseKey(status.getPath().getName()).get(Constants.TEMPLATE_KEY));
            }
        }
    }

    private String findData(String path, String key) {
        for (FileStatus status : list(path)) {
            if (status.isFile() && StringUtils.equals(status.getPath().getName().split(Constants.OWNER_PREFIX)[0], key)) {
                return status.getPath().getName();
            }
        }
        return null;
    }

    private String findRefinedDraft(String key) {
        return findData(properties.getDraftRefined(), key);
    }

    private String findCurrentRefined(String key) {
        return findData(properties.getCurrentRefined(), key);
    }

    private boolean deleteDraft(String key) {
        String filename = findData(properties.getDraftMd(), key);
        if (filename == null) {
            return false;
        }
        delete(new Path(properties.getDraftMd(), filename));
        return true;
    }

    private boolean deleteRefinedDraft(String key) {
        String filename = findRefinedDraft(key);
        if (filename == null) {
            return false;
        }
        delete(new Path(properties.getDraftRefined(), filename));
        return true;
    }

    private String formatVersion(String version) {
        Date dateVersion = new Date();
        if (version != null) {
            try {
                dateVersion = new SimpleDateFormat(Constants.VERSION_FORMAT).parse(version);
            } catch (ParseException e) {
                logger.error("HadoopDatastoreService - unable to parse version from file. Using current date instead");
            }
        }
        return new SimpleDateFormat(Constants.HADOOP_VERS_FORMAT).format(dateVersion);
    }

    private <T> T readJson(Path path, Class<T> type) {
        try {
            return mapper.readValue(readText(path), type);
        } catch (IOException e) {
            logger.error("Unable to read data - " + e.getMessage(), e);
            throw new AppInternalError("Unable to read data - " + e.getMessage());
        }
    }

    private void writeJson(String directory, String filename, Object value, boolean overwrite, boolean append) {
        try {
            writeText(directory, filename, mapper.writeValueAsString(value), overwrite, append);
        } catch (IOException e) {
            logger.error("unable to serialize data for Hadoop: " + e.getMessage(), e);
            throw new AppInternalError("unable to serialize data for Hadoop: " + e.getMessage());
        }
    }

    private String readText(Path path) throws IOException {
        try (FSDataInputStream inputStream = fileSystem.open(path)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeText(String directory, String filename, String data, boolean overwrite, boolean append) {
        Path directoryPath = new Path(directory);
        Path filePath = new Path(directoryPath, filename);
        try {
            fileSystem.mkdirs(directoryPath);
            if (!overwrite && !append && fileSystem.exists(filePath)) {
                throw new KeyExistsException();
            }
            if (append && fileSystem.exists(filePath)) {
                try (FSDataOutputStream outputStream = fileSystem.append(filePath)) {
                    outputStream.write(data.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                try (FSDataOutputStream outputStream = fileSystem.create(filePath, overwrite)) {
                    outputStream.write(data.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            logger.error("unable to write data to Hadoop: " + e.getMessage(), e);
            throw new AppInternalError("unable to write data to Hadoop: " + e.getMessage());
        }
    }

    private List<FileStatus> list(String path) {
        try {
            Path hadoopPath = new Path(path);
            if (!fileSystem.exists(hadoopPath)) {
                return Collections.emptyList();
            }
            return Arrays.asList(fileSystem.listStatus(hadoopPath));
        } catch (IOException e) {
            logger.error("unable to list Hadoop path: " + path, e);
            throw new AppInternalError("unable to list Hadoop path: " + path);
        }
    }

    private FileStatus[] glob(Path path) {
        try {
            FileStatus[] statuses = fileSystem.globStatus(path);
            return statuses == null ? new FileStatus[0] : statuses;
        } catch (IOException e) {
            logger.error("unable to glob Hadoop path: " + path, e);
            throw new AppInternalError("unable to glob Hadoop path: " + path);
        }
    }

    private long count(String path) {
        try {
            Path hadoopPath = new Path(path);
            if (!fileSystem.exists(hadoopPath)) {
                return 0;
            }
            ContentSummary contentSummary = fileSystem.getContentSummary(hadoopPath);
            return contentSummary.getFileCount();
        } catch (IOException e) {
            logger.error("unable to count Hadoop path: " + path, e);
            throw new AppInternalError("unable to count Hadoop path: " + path);
        }
    }

    private void rename(Path source, Path destination) {
        try {
            fileSystem.mkdirs(destination.getParent());
            if (!fileSystem.rename(source, destination)) {
                throw new AppInternalError("unable to move Hadoop file");
            }
        } catch (IOException e) {
            logger.error("unable to move Hadoop file: " + e.getMessage(), e);
            throw new AppInternalError("unable to move Hadoop file: " + e.getMessage());
        }
    }

    private void delete(Path path) {
        try {
            fileSystem.delete(path, false);
        } catch (IOException e) {
            logger.error("unable to delete Hadoop file: " + e.getMessage(), e);
            throw new AppInternalError("unable to delete Hadoop file: " + e.getMessage());
        }
    }
}
