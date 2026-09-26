package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class PlayerReport {

    private final long id;
    private final UUID reportedUuid;
    private final UUID reporterUuid;
    private final String reason;
    private final long createdAt;
    private final boolean solved;
    private final UUID solvedByUuid;
    private final long solvedAt;

    public PlayerReport(long id, UUID reportedUuid, UUID reporterUuid, String reason, long createdAt) {
        this(id, reportedUuid, reporterUuid, reason, createdAt, false, null, 0L);
    }

    public PlayerReport(long id, UUID reportedUuid, UUID reporterUuid, String reason, long createdAt, boolean solved,
            UUID solvedByUuid, long solvedAt) {
        this.id = id;
        this.reportedUuid = reportedUuid;
        this.reporterUuid = reporterUuid;
        this.reason = reason;
        this.createdAt = createdAt;
        this.solved = solved;
        this.solvedByUuid = solvedByUuid;
        this.solvedAt = solvedAt;
    }

    public long getId() {
        return id;
    }

    public UUID getReportedUuid() {
        return reportedUuid;
    }

    public UUID getReporterUuid() {
        return reporterUuid;
    }

    public String getReason() {
        return reason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isSolved() {
        return solved;
    }

    public UUID getSolvedByUuid() {
        return solvedByUuid;
    }

    public long getSolvedAt() {
        return solvedAt;
    }
}
