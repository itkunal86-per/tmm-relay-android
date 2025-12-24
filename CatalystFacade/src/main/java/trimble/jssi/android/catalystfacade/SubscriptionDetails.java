package trimble.jssi.android.catalystfacade;

import java.util.Date;

public class SubscriptionDetails{

    public SubscriptionDetails(String subscriptionName, Date issueDate, Date expiryDate) {
        super();
        this.subscriptionName = subscriptionName;
        this.issueDate = issueDate;
        this.expiryDate = expiryDate;
    }

    public String getSubscriptionName() {
        return subscriptionName;
    }
    public Date getIssueDate() {
        return issueDate;
    }
    public Date getExpiryDate() {
        return expiryDate;
    }

    private final Date issueDate;
    private final Date expiryDate;
    private final String subscriptionName;
}
