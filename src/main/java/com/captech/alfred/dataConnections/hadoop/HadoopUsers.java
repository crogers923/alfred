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
import com.captech.alfred.authentication.Role;
import com.captech.alfred.authentication.User;
import com.captech.alfred.dataConnections.DataUserStoreService;
import com.captech.alfred.dataConnections.UsersProperties;
import com.captech.alfred.exceptions.AppInternalError;
import com.captech.alfred.exceptions.KeyExistsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Profile("!local")
@EnableAutoConfiguration
@EnableConfigurationProperties(UsersProperties.class)
public class HadoopUsers extends DataUserStoreService {

    private static final Logger logger = LoggerFactory.getLogger(HadoopUsers.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    UsersProperties properties;

    @Autowired
    private FileSystem fileSystem;

    @Override
    public User findByUsername(String username) {
        for (FileStatus status : list(properties.getAuthPath())) {
            if (status.isFile()) {
                String filename = status.getPath().getName();
                if (filename.startsWith(username)) {
                    return readJson(status.getPath(), User.class);
                }
            }
        }
        return null;
    }

    public boolean verifyExistingUser(String username) {
        for (FileStatus status : list(properties.getAuthPath())) {
            if (status.isFile() && status.getPath().getName().startsWith(username)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String deleteUser(String username) {
        String movedFiles = "";
        if (!verifyExistingUser(username)) {
            return null;
        }
        for (FileStatus status : list(properties.getAuthPath())) {
            if (status.isFile()) {
                String filename = status.getPath().getName();
                if (filename.startsWith(username)) {
                    Path destination = new Path(properties.getOldAuthPath(), filename + "_"
                            + new SimpleDateFormat(Constants.HADOOP_VERS_FORMAT).format(new Date()));
                    rename(status.getPath(), destination);
                    if (!movedFiles.isEmpty()) {
                        movedFiles = movedFiles + ",";
                    }
                    movedFiles = movedFiles + filename;
                }
            }
        }
        return movedFiles;
    }

    @Override
    public void writeNewUser(User user) {
        writeJson(properties.getAuthPath(), generateFilename(user), user, false, false);
    }

    @Override
    public void updateUser(User user) {
        if (verifyExistingUser(user.getUsername())) {
            deleteUser(user.getUsername());
        }
        writeNewUser(user);
    }

    @Override
    public List<Role> getRoles() {
        List<Role> roles = new ArrayList<>();
        for (FileStatus status : list(properties.getAuthoritiesPath())) {
            if (status.isFile() && !status.getPath().getName().startsWith("ALL_PERMS")) {
                roles.add(readJson(status.getPath(), Role.class));
            }
        }
        return roles;
    }

    @Override
    public List<String> getPermissions() {
        List<String> permissions = new ArrayList<>();
        Path permissionsPath = new Path(properties.getAuthoritiesPath(), "ALL_PERMS");
        if (!exists(permissionsPath)) {
            return permissions;
        }
        String text = readText(permissionsPath).replaceAll("\n", "");
        if (StringUtils.isNotEmpty(text)) {
            permissions.addAll(Arrays.asList(text.split(",")));
        }
        return permissions;
    }

    @Override
    public void addPermission(String permission) {
        writeText(properties.getAuthoritiesPath(), "ALL_PERMS",
                "," + StringUtils.upperCase(permission), true, true);
    }

    @Override
    public void addRole(Role role) {
        writeJson(properties.getAuthoritiesPath(), "ROLE_" + role.getName(), role, false, false);
    }

    @Override
    public void editRole(Role role) {
        writeJson(properties.getAuthoritiesPath(), "ROLE_" + role.getName(), role, true, false);
    }

    public Role getRole(String name) {
        Path rolePath = new Path(properties.getAuthoritiesPath(), name);
        if (exists(rolePath)) {
            return readJson(rolePath, Role.class);
        }
        return null;
    }

    @Override
    public Set<String> listUsers() {
        Set<String> users = new HashSet<>();
        for (FileStatus status : list(properties.getAuthPath())) {
            if (status.isFile()) {
                users.add(status.getPath().getName().split("_roles_")[0]);
            }
        }
        return users;
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

    private String readText(Path path) {
        try (FSDataInputStream inputStream = fileSystem.open(path)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Unable to read data - " + e.getMessage(), e);
            throw new AppInternalError("Unable to read data - " + e.getMessage());
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
                return new ArrayList<>();
            }
            return Arrays.asList(fileSystem.listStatus(hadoopPath));
        } catch (IOException e) {
            logger.error("unable to list Hadoop path: " + path, e);
            throw new AppInternalError("unable to list Hadoop path: " + path);
        }
    }

    private boolean exists(Path path) {
        try {
            return fileSystem.exists(path);
        } catch (IOException e) {
            logger.error("unable to check Hadoop path: " + path, e);
            throw new AppInternalError("unable to check Hadoop path: " + path);
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
}
