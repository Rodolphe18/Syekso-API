package dev.rodolphe.accesscontrol.db

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.kotlinx.KotlinSerializerCodecProvider
import org.mindrot.jbcrypt.BCrypt

/**
 * Typed access to the three collections. Doors live inside their building document, so there is no
 * doors collection — you reach a door through its building.
 */
class MongoStorage(db: MongoDatabase) {

    val users = db.getCollection<UserDoc>("users")
    val buildings = db.getCollection<BuildingDoc>("buildings")
    val activationCodes = db.getCollection<ActivationCodeDoc>("activation_codes")
    val pinCodes = db.getCollection<PinCodeDoc>("pin_codes")

    /**
     * Seeds one resident, one building with two doors, and one unredeemed activation code — enough
     * to demo login → activate → see doors. Activation codes are seeded here because the *manager*
     * issues them in the real product, and the manager app is out of scope.
     */
    suspend fun seedIfEmpty() {
        if (users.find().firstOrNull() != null) return

        val buildingId = "bld-montmartre"
        buildings.insertOne(
            BuildingDoc(
                id = buildingId,
                name = "Résidence Montmartre",
                doors = listOf(
                    DoorDoc(id = "door-hall", name = "Porte d'entrée", bleLocalName = "OSKEY-HALL-01"),
                    DoorDoc(id = "door-garage", name = "Garage", bleLocalName = "OSKEY-GARAGE-01"),
                ),
            ),
        )
        users.insertOne(
            UserDoc(
                id = "user-rodolphe",
                email = "rodolphe@example.com",
                passwordHash = BCrypt.hashpw("password", BCrypt.gensalt()),
                displayName = "Rodolphe",
            ),
        )
        activationCodes.insertOne(
            ActivationCodeDoc(code = "MONT-2026", buildingId = buildingId),
        )
    }
}

/**
 * Builds the Mongo client from the MONGODB_URI environment variable — never a hardcoded string, so
 * the Atlas credentials stay out of the repo. Registers the kotlinx.serialization BSON codec so the
 * @Serializable documents map directly.
 */
fun connectMongo(): MongoStorage {
    val uri = System.getenv("MONGODB_URI")
        ?: error("MONGODB_URI environment variable is required (set it in the IntelliJ run config)")
    val dbName = System.getenv("MONGODB_DB") ?: "accesscontrol"

    val codecRegistry = CodecRegistries.fromRegistries(
        MongoClientSettings.getDefaultCodecRegistry(),
        CodecRegistries.fromProviders(KotlinSerializerCodecProvider()),
    )
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(uri))
        .codecRegistry(codecRegistry)
        .build()

    val database = MongoClient.create(settings).getDatabase(dbName)
    return MongoStorage(database)
}
