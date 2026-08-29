# AGENTS.md — AccessControllerServer

Le backend de l'application Android **Syekso** (dépôt séparé : `C:/Users/rodol/AndroidStudioProjects/Syekso`).
MongoDB Atlas, authentification JWT, clé partagée pour les interphones de hall, et un relais WebSocket
qui porte la signalisation WebRTC des appels de porte.

**Un seul projet Gradle, aucun sous-projet** — Spring Boot 4 / Java 21 à la racine, sur le port 8080.

Le serveur a commencé sa vie en Ktor + Kotlin. Il a été **supprimé le 2026-08-29**, une fois la
réécriture arrivée à parité sur les quinze routes plus `/ws`, et le sous-projet `:spring` qui hébergeait
cette réécriture a été aplati à la racine dans la foulée. Si une comparaison avec l'original devient un
jour nécessaire — le format de fil a été porté octet pour octet — il est dans l'historique au commit
`9cedab8`. Ne pas le ressusciter dans le répertoire de travail.

**Les packages sont organisés par feature, pas par couche :** `auth`, `doors`, `access`, `intercom`,
`feed`, `signaling`, plus `users` (agrégat partagé, assumé comme tel), `shared` (vocabulaire d'erreur)
et `config` (racine de composition). Les classes sont package-private partout où la feature n'a pas
besoin de les exposer — c'est tout l'intérêt du découpage, et c'est le compilateur qui l'applique.
Avant de rendre quelque chose `public`, se demander si l'appelant ne pourrait pas simplement vivre dans
le même package.

## Règles d'engagement

- **Le propriétaire commite et pousse.** Ne jamais lancer `git commit` ni `git push`. Un `git status`
  chargé est normal et délibéré — ce n'est pas un désordre à ranger.
- **Tu ne peux pas démarrer l'application.** `MONGODB_URI` n'existe que dans les configurations de run
  IntelliJ, jamais au niveau machine. Demander au propriétaire de lancer le serveur, ne pas chercher à
  contourner. (Piège associé : une configuration créée depuis l'icône de gouttière ne porte aucune
  variable d'environnement.)

## Commandes

```bash
./gradlew test        # 67 tests — doivent rester verts
./gradlew bootRun     # échoue sans MONGODB_URI ; c'est au propriétaire de le lancer
```

Une fois le serveur démarré, `scripts/smoke-cutover.ps1` exécute 18 contrôles de bout en bout
(`-Port 8081` pour viser une instance parallèle, `-ReadOnly` pour sauter le seul contrôle qui écrit).

## Choix de langage et de bibliothèques — non négociables

- **Java, pas Kotlin.** Ce dépôt est une vitrine pour des offres backend Java. En cas d'arbitrage entre
  une bibliothèque Java et une Kotlin, prendre la Java.
- **Jackson uniquement** pour la sérialisation. kotlinx.serialization est abandonné côté serveur.

## Pièges Spring Boot 4 — à vérifier en premier quand quelque chose semble ignoré

- **`spring.data.mongodb.*` a été renommé `spring.mongodb.*`.** L'ancien nom est ignoré *en silence* et
  le client retombe sur `mongodb://localhost/test`. Aucun avertissement, aucune erreur de substitution.
  La documentation en ligne est encore massivement en 3.x : soupçonner une propriété renommée dès qu'une
  configuration paraît sans effet.
- **Boot 4 embarque Jackson 3**, dont le paquet racine est `tools.jackson` et non `com.fasterxml.jackson`.
- **Spring Data MongoDB traite *toute* propriété nommée `id` comme l'identifiant** et la lit depuis
  `_id` — y compris dans un type imbriqué sans `@Document`. D'où `Door` qui nomme son composant
  `doorId` avec `@Field("id")`. `@Field` seul n'y suffit pas.
- **Boxer ce qui a une valeur par défaut sémantique.** Les documents écrits par le serveur Kotlin
  omettent les défauts : un `boolean singleUse` primitif lirait `false` et retransformerait tout PIN à
  usage unique déjà consommé en code réutilisable — une régression de sécurité, pas un détail cosmétique.
  Voir `PinCode` et `SignalingMessage.IceCandidate`.
- **Les tranches de test sont éclatées par technologie.** `@AutoConfigureMockMvc` vit dans
  `org.springframework.boot.webmvc.test.autoconfigure` et exige un
  `testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")` explicite —
  `spring-boot-starter-test` ne l'apporte plus.
- **`ServletServerContainerFactoryBean` exige un vrai Tomcat.** Il porte `@Profile("!test")` ; les tests
  d'intégration portent `@ActiveProfiles("test")`. Sans cela, chaque `@SpringBootTest` échoue sur
  `Attribute 'jakarta.websocket.server.ServerContainer' not found in ServletContext`.
- **Spring Security refuse au démarrage une chaîne de filtres inatteignable.** Une chaîne attrape-tout
  (sans `securityMatcher`) placée avant une chaîne étroite lève `UnreachableFilterChainException` et le
  contexte ne démarre pas.
- **Aucun `_class` n'est écrit dans les documents** (`config/MongoConfig`). Le défaut de Spring Data
  estampille chaque document du nom pleinement qualifié de sa classe, ce qui fait dépendre le contenu de
  la base de l'arborescence des sources : déplacer une classe de package laisse alors des documents
  pointant vers une classe disparue. Aucune collection ici n'est polymorphe, l'indice n'apportait rien.

## Le format de fil est un contrat — prudence

`signaling/SignalingMessage` et les records de requête et de réponse de chaque feature sont analysés par
**deux applications Android déployées** qui utilisent kotlinx.serialization. Renommer un champ JSON, ou
un nom de `type` de message, les casse immédiatement — et aucune des deux ne sera redéployée pour
arranger le serveur. Les onze noms de types de signalisation sont figés.

Deux écarts avec le serveur Kotlin sont délibérés et ne sont pas des bugs : la validation rend 400 là où
Ktor rendait 200, et la libération d'un code interphone filtre sur `singleUse != false` plutôt que
`== true` (les documents anciens n'ont pas ce champ du tout).

## Modèle de sécurité, en un paragraphe

Deux `SecurityFilterChain`, sélectionnées par chemin. `@Order(1)` matche `/intercom/**` et authentifie
le boîtier de hall par l'en-tête `X-Intercom-Key` ; `@Order(2)` est l'attrape-tout et authentifie les
résidents par un JWT porteur. Ce sont des **alternatives, pas des couches** — une requête vers
`/intercom/**` ne rencontre jamais `JwtTokenVerifierFilter`. Aucun des deux filtres ne refuse quoi que
ce soit : ils enregistrent qui appelle, et `AuthorizationFilter` décide. Une troisième forme
d'autorisation — « cette porte est-elle la tienne ? » — dépend des données, vit dans les services, et
aucune règle d'URL ne saurait l'exprimer.

## Conventions de test

- Tests unitaires : Mockito en style BDD (`given` / `then`), sans contexte Spring.
- Tests d'intégration : `@SpringBootTest` + MockMvc + `@MockitoBean` + `@ActiveProfiles("test")`. Pas
  `@WebMvcTest` — le but est justement d'exercer la vraie `SecurityConfig`, qu'une tranche exclut.
- `@DisplayName` en français, décrivant la propriété testée.
- Les commentaires expliquent **pourquoi**, en nommant le piège évité. S'aligner sur ce ton, c'est le
  style de la maison.
- Vérifier l'appel plutôt qu'assertionner une charge utile bouchonnée : un mock non bouchonné rend
  `null` et le contrôleur répond quand même 200, donc un `isOk()` seul ne démontre souvent rien.

## Données de démarrage

`config/MongoSeeder` (un `ApplicationRunner`, `@Profile("!test")`) remplit une base vide : utilisateur
`rodolphe@example.com` / `password`, immeuble « Résidence Montmartre » avec les portes `OSKEY-HALL-01`
et `OSKEY-GARAGE-01`, code d'activation non consommé `MONT-2026`, plus 1000 lignes de feed et l'index
composé dont dépend la requête par curseur. Les noms BLE sont ceux qu'annonce le firmware ESP32 — en
renommer un casse la démonstration matérielle.

## Ne jamais commiter

`MONGODB_URI`, les identifiants Atlas, les secrets JWT, les clés de compte de service. La configuration
les lit dans l'environnement, c'est tout l'intérêt.
