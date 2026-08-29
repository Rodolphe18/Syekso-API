package dev.rodolphe.accesscontrol.doors;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/** A building, with its doors embedded rather than held in a separate collection. */
@Document(collection = "buildings")
public record Building(
        @Id String id,
        String name,
        List<Door> doors
) {
    public Building {
        doors = doors == null ? List.of() : List.copyOf(doors);
    }
}
