package dev.rodolphe.accesscontrol.doors;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * No method of its own, and that is the point: both places the Kotlin server queries buildings are
 * already covered by what {@code MongoRepository} inherits.
 *
 * <ul>
 *   <li>{@code buildings.find(Filters.eq("_id", id))} → {@code findById(id)}</li>
 *   <li>{@code buildings.find(Filters.in("_id", user.buildingIds))} → {@code findAllById(ids)}</li>
 * </ul>
 */
public interface BuildingRepository extends MongoRepository<Building, String> {
}
