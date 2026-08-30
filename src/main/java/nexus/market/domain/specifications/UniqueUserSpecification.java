package nexus.market.domain.specifications;

import java.util.Objects;

import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;

/**
 * Verifica que el {@code email} y el {@code documentId} no estén ya
 * registrados en la plataforma (regla de unicidad global del {@code User}).
 *
 * <p>No depende de puertos de infraestructura: define una interfaz funcional
 * anidada {@link UserLookup} que el servicio o adaptador implementa, lo que
 * mantiene el paquete {@code domain} 100% libre de frameworks.</p>
 */
public class UniqueUserSpecification {

    public interface UserLookup {
        boolean existsByEmail(Email email);

        boolean existsByDocumentId(DocumentId documentId);
    }

    private final UserLookup userLookup;

    public UniqueUserSpecification(UserLookup userLookup) {
        this.userLookup = Objects.requireNonNull(userLookup, "userLookup es obligatorio");
    }

    public boolean isSatisfiedBy(Email email, DocumentId documentId) {
        Objects.requireNonNull(email, "email es obligatorio");
        Objects.requireNonNull(documentId, "documentId es obligatorio");
        return !userLookup.existsByEmail(email)
                && !userLookup.existsByDocumentId(documentId);
    }
}