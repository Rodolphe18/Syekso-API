package dev.rodolphe.accesscontrol.doors;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Looking a code up is {@code findById}, inherited.
 *
 * <p>Redeeming it is not here for the same reason claiming a PIN is not in {@link PinCodeRepository}:
 * it is a conditional atomic update — mark this code as redeemed <em>only if</em> nobody redeemed it
 * first — which an interface method cannot express. It arrives with {@code MongoTemplate} in
 * iteration 2.
 */
public interface ActivationCodeRepository extends MongoRepository<ActivationCode, String> {
}
