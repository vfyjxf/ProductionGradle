package dev.vfyjxf.gradle.launcher.core.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.nio.file.Path;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(pathModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER.copy();
    }

    private static SimpleModule pathModule() {
        SimpleModule module = new SimpleModule("LaunchPathModule");
        module.addSerializer(Path.class, new JsonSerializer<>() {
            @Override
            public void serialize(Path value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        module.addDeserializer(Path.class, new JsonDeserializer<>() {
            @Override
            public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return Path.of(parser.getValueAsString());
            }
        });
        return module;
    }
}
