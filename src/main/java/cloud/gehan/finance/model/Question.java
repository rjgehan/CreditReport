package cloud.gehan.finance.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Something asked of Claude on the Claude page, and its answer once it comes. */
@Entity
@Table(name = "question")
public class Question {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String text;

    /** RUNNING, DONE or FAILED. */
    @Column(nullable = false)
    private String status = RUNNING;

    /** For "can I afford it" questions: yes, careful or no; otherwise none. */
    private String verdict;

    /** The answer, one line per line. */
    @Column(length = 4000)
    private String lines;

    /** The transactions the answer is about, comma-separated ids. */
    @Column(length = 2000)
    private String transactionIds;

    @Column(length = 500)
    private String error;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant answeredAt;

    protected Question() {
    }

    public Question(String text) {
        this.text = text;
    }

    public Long getId() { return id; }
    public String getText() { return text; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getLines() { return lines; }
    public void setLines(String lines) { this.lines = lines; }
    public String getTransactionIds() { return transactionIds; }
    public void setTransactionIds(String transactionIds) { this.transactionIds = transactionIds; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(Instant answeredAt) { this.answeredAt = answeredAt; }
}
