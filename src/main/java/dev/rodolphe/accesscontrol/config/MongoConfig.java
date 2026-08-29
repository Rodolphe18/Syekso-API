package dev.rodolphe.accesscontrol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * Stops Spring Data writing a {@code _class} type hint into every document.
 *
 * <p><strong>Why this matters here, concretely.</strong> By default the mapper stamps each document it
 * writes with the <em>fully qualified</em> name of the Java class that produced it. Do that, and the
 * stored data starts depending on your package layout: moving {@code PinCode} from {@code db} to
 * {@code access} — which is exactly what the feature-package refactor did — leaves live documents
 * pointing at a class that no longer exists. Persistence must not be able to break because someone
 * reorganised source folders.
 *
 * <p><strong>Why it is safe to drop.</strong> The hint exists to disambiguate polymorphic reads: it
 * tells the mapper which subtype to instantiate when a collection holds several. Not one collection
 * here does — {@code users}, {@code buildings}, {@code activation_codes}, {@code pin_codes} and
 * {@code feed_items} each hold exactly one shape, and every read names its type. The field was pure
 * noise, and noise with a coupling attached.
 *
 * <p>It also restores parity with the Kotlin server this replaced: its kotlinx BSON codec never wrote
 * {@code _class}, so documents created before the migration have none. Reading is tolerant either way
 * — an unresolvable hint falls back to the declared type — but writing it again would slowly split the
 * collections into two shapes for no benefit.
 */
@Configuration
public class MongoConfig {

    @Bean
    public MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory factory,
                                                       MongoMappingContext context) {
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(factory);
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, context);
        // null alias key = write no type hint at all.
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        return converter;
    }
}
