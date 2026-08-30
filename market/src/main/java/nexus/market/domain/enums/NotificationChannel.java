package nexus.market.domain.enums;

/**
 * Canal de notificación usado por el adaptador de notificaciones.
 */
public enum NotificationChannel {

    /** Correo electrónico. */
    EMAIL,

    /** Mensaje de texto. */
    SMS,

    /** Notificación push en aplicación móvil. */
    PUSH_NOTIFICATION
}