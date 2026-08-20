package com.company.logicdelete.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Path;

public final class ConfigLoader {
    private ConfigLoader() {}
    public static LogicDeleteConfig load(Path path) throws IOException {
        ObjectMapper mapper=new ObjectMapper(new YAMLFactory()).registerModule(new JavaTimeModule());
        LogicDeleteConfig config=mapper.readValue(path.toFile(), LogicDeleteConfig.class);
        if(config.getTables()==null||config.getTables().isEmpty()) throw new IllegalArgumentException("Configuration must contain at least one controlled table");
        for(LogicDeleteConfig.ControlledTable t:config.getTables()) if(t.getTable()==null||t.getTable().trim().isEmpty()) throw new IllegalArgumentException("Controlled table name must not be empty");
        return config;
    }
}
