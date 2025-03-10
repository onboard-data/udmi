
package udmi.schema;

import java.util.Date;
import javax.annotation.processing.Generated;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;


/**
 * Gateway State
 * <p>
 * [Gateway Documentation](../docs/specs/gateway.md)
 * 
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "timestamp",
    "version",
    "devices"
})
@Generated("jsonschema2pojo")
public class GatewayState {

    /**
     * Not included in messages published by devices. Part of message subblocks within cloud pipeline. RFC 3339 Timestamp the payload was generated
     * 
     */
    @JsonProperty("timestamp")
    @JsonPropertyDescription("Not included in messages published by devices. Part of message subblocks within cloud pipeline. RFC 3339 Timestamp the payload was generated")
    public Date timestamp;
    /**
     * Version of the UDMI schema
     * 
     */
    @JsonProperty("version")
    @JsonPropertyDescription("Version of the UDMI schema")
    public String version;
    @JsonProperty("devices")
    public Object devices;

    @Override
    public int hashCode() {
        int result = 1;
        result = ((result* 31)+((this.version == null)? 0 :this.version.hashCode()));
        result = ((result* 31)+((this.devices == null)? 0 :this.devices.hashCode()));
        result = ((result* 31)+((this.timestamp == null)? 0 :this.timestamp.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof GatewayState) == false) {
            return false;
        }
        GatewayState rhs = ((GatewayState) other);
        return ((((this.version == rhs.version)||((this.version!= null)&&this.version.equals(rhs.version)))&&((this.devices == rhs.devices)||((this.devices!= null)&&this.devices.equals(rhs.devices))))&&((this.timestamp == rhs.timestamp)||((this.timestamp!= null)&&this.timestamp.equals(rhs.timestamp))));
    }

}
