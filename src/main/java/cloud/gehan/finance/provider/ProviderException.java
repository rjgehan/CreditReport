package cloud.gehan.finance.provider;

/** A provider refused or failed. The message is safe to show on the Accounts page. */
public class ProviderException extends RuntimeException {

    private final boolean needsReauth;

    public ProviderException(String message, boolean needsReauth) {
        super(message);
        this.needsReauth = needsReauth;
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
        this.needsReauth = false;
    }

    /** The bank wants you to sign in again before it will share anything. */
    public boolean needsReauth() {
        return needsReauth;
    }
}
