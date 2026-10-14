package cloud.gehan.finance.model;

public enum ConnectionStatus {
    OK,
    ERROR,
    /** The bank wants you to sign in again (Teller enrollment.disconnected, SimpleFIN 403). */
    NEEDS_REAUTH
}
