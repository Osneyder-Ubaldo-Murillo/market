package nexus.market.domain.models;

import java.util.List;

import org.junit.jupiter.api.Test;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.CommercialStatus;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SellerStatus;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.TaxId;
import nexus.market.domain.valueobjects.UserStatus;
import nexus.market.domain.valueobjects.WarehouseId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba el ciclo de vida de {@code User}, {@code Buyer} y {@code Seller}
 * (transiciones de estado y creación de perfiles).
 */
class UserLifecycleTest {

    private static final FullName FULL_NAME = FullName.of("Ana Pérez");
    private static final Email EMAIL = Email.of("ana.perez@example.com");
    private static final DocumentId DOCUMENT = DocumentId.of("12345678");

    private static Address address() {
        return Address.of("Calle 1", "12A", null, "Centro", "Bogotá",
                "Cundinamarca", "110111", "Colombia");
    }

    @Test
    void createCreaUsuarioActivoConRolInmutable() {
        User user = User.create(FULL_NAME, EMAIL, DOCUMENT, SystemRole.BUYER);
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(SystemRole.BUYER, user.getRole());
        assertEquals(EMAIL, user.getEmail());
    }

    @Test
    void transicionesDeEstadoDeUnUsuario() {
        User user = User.create(FULL_NAME, EMAIL, DOCUMENT, SystemRole.SELLER);

        user.block();
        assertEquals(UserStatus.BLOCKED, user.getStatus());

        user.activate();
        assertEquals(UserStatus.ACTIVE, user.getStatus());

        user.deactivate();
        assertEquals(UserStatus.INACTIVE, user.getStatus());

        assertThrows(BusinessException.class, () -> user.block());
    }

    @Test
    void soloUnUsuarioBloqueadoPuedeReactivarse() {
        User user = User.create(FULL_NAME, EMAIL, DOCUMENT, SystemRole.BUYER);
        assertThrows(BusinessException.class, user::activate);
    }

    @Test
    void compradorActivoPuedeGestionarDirecciones() {
        User user = User.create(FULL_NAME, EMAIL, DOCUMENT, SystemRole.BUYER);
        Buyer buyer = Buyer.create(user.getUserId(), address());
        assertEquals(CommercialStatus.ACTIVE, buyer.getCommercialStatus());

        buyer.block();
        assertEquals(CommercialStatus.BLOCKED, buyer.getCommercialStatus());

        buyer.activate();
        assertEquals(CommercialStatus.ACTIVE, buyer.getCommercialStatus());

        buyer.addAddress(address());
        assertEquals(1, buyer.getAdditionalAddresses().size());
    }

    @Test
    void vendedorSeCreaPendienteDeVerificacionYSoloAdministradorAprueba() {
        User user = User.create(FullName.of("Carlos Ruiz"), Email.of("carlos.ruiz@example.com"),
                DocumentId.of("98765432"), SystemRole.SELLER);
        Seller seller = Seller.create(user.getUserId(), BusinessName.of("Comercial Ruiz"),
                TaxId.of("900123456"));

        assertEquals(SellerStatus.PENDING_VERIFICATION, seller.getStatus());
        assertThrows(BusinessException.class, () -> seller.block());

        seller.approve();
        assertEquals(SellerStatus.ACTIVE, seller.getStatus());

        seller.block();
        assertEquals(SellerStatus.BLOCKED, seller.getStatus());
        seller.activate();
        assertEquals(SellerStatus.ACTIVE, seller.getStatus());
    }

    @Test
    void vendedorNoPuedeDuplicarBodegaNiExcederLimiteDeDiez() {
        User user = User.create(FullName.of("María López"), Email.of("maria.lopez@example.com"),
                DocumentId.of("87654321"), SystemRole.SELLER);
        Seller seller = Seller.create(user.getUserId(), BusinessName.of("Tienda María"),
                TaxId.of("800123456"));
        seller.approve();

        WarehouseId first = WarehouseId.generate();
        seller.addWarehouse(first);
        assertThrows(BusinessException.class, () -> seller.addWarehouse(first));

        for (int i = 1; i < Seller.MAX_WAREHOUSES; i++) {
            seller.addWarehouse(WarehouseId.generate());
        }
        assertThrows(BusinessException.class,
                () -> seller.addWarehouse(WarehouseId.generate()));
        assertEquals(Seller.MAX_WAREHOUSES, seller.getWarehouses().size());
    }

    @Test
    void listaDeDireccionesAdicionalesEsInmutable() {
        Buyer buyer = Buyer.create(User.create(FULL_NAME, EMAIL, DOCUMENT, SystemRole.BUYER).getUserId(), address());
        buyer.addAddress(address());

        List<Address> additional = buyer.getAdditionalAddresses();
        assertTrue(additional.contains(address()));
        assertThrows(UnsupportedOperationException.class,
                () -> additional.add(address()));
    }
}