/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.vlanbasedsr;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ConnectPoint;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;

public class NameConfig extends Config<ApplicationId> {

  public static final String SEGMENTID = "segmentId";
  public static final String SUBNETS = "subnets";
  public static final String HOSTS = "hosts";

  @Override
  public boolean isValid() {
    return hasFields(SEGMENTID, SUBNETS, HOSTS);
  }

  public Map<DeviceId, VlanId> getSegmentId() {
    Map<DeviceId, VlanId> m = new HashMap();
    JsonNode n = node().get(SEGMENTID);
    Iterator<String> keys = n.fieldNames();
    while(keys.hasNext()) {
	    String key = keys.next();
	    DeviceId deviceId = DeviceId.deviceId(key);
	    VlanId vlanId = VlanId.vlanId(n.get(key).asText());
	    m.put(deviceId, vlanId);
    }
    return m;
  }

  public Map<IpPrefix, DeviceId> getSubnets() {
    Map<IpPrefix, DeviceId> m = new HashMap();
    JsonNode n = node().get(SUBNETS);
    Iterator<String> keys = n.fieldNames();
    while(keys.hasNext()) {
	    String key = keys.next();
	    IpPrefix ip = IpPrefix.valueOf(key);
	    DeviceId deviceId = DeviceId.deviceId(n.get(key).asText());
	    m.put(ip, deviceId);
    }
    return m;
  }

  public Map<MacAddress, ConnectPoint> getHosts() {
    Map<MacAddress, ConnectPoint> m = new HashMap();
    JsonNode n = node().get(HOSTS);
    Iterator<String> keys = n.fieldNames();
    while(keys.hasNext()) {
	    String key = keys.next();
	    MacAddress mac = MacAddress.valueOf(key);
	    ConnectPoint cp = ConnectPoint.deviceConnectPoint(n.get(key).asText());
	    m.put(mac, cp);
    }
    return m;
  }

}
