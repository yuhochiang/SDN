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

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.topology.PathService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ElementId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.Path;
import org.onosproject.net.Link;
import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.VlanId;

import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;


/** Sample Network Configuration Service Application */
@Component(immediate = true)
public class AppComponent {

	private final Logger log = LoggerFactory.getLogger(getClass());
	private final NameConfigListener cfgListener = new NameConfigListener();
	private final ConfigFactory factory =
			new ConfigFactory<ApplicationId, NameConfig>(
					APP_SUBJECT_FACTORY, NameConfig.class, "vlanbasedsrConfig") {
				@Override
				public NameConfig createConfig() {
					return new NameConfig();
				}
			};

	private ApplicationId appId;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected NetworkConfigRegistry cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;
	
	// to handle packet
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	// to install flow rules
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowRuleService flowRuleService;

	// to get path
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PathService pathService;

	private VlanbasedsrProcessor processor = new VlanbasedsrProcessor();

	private Map<DeviceId, VlanId> segmentId ;
	private Map<IpPrefix, DeviceId> subnets ;
	private Map<MacAddress, ConnectPoint> hosts;
	

	@Activate
	protected void activate() {
		appId = coreService.registerApplication("nctu.winlab.vlanbasedsr");
		cfgService.addListener(cfgListener);
		cfgService.registerConfigFactory(factory);
		// create processor
		packetService.addProcessor(processor, PacketProcessor.director(2));
		// request IPv4 packet
		packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(), PacketPriority.REACTIVE, appId);
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		cfgService.removeListener(cfgListener);
		cfgService.unregisterConfigFactory(factory);
		// cancel rquest for packet-in
		packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(), PacketPriority.REACTIVE, appId);
		// clean flow rules
		flowRuleService.removeFlowRulesById(appId);
		// remove processor
		packetService.removeProcessor(processor);

		log.info("Stopped");
	}

	private class NameConfigListener implements NetworkConfigListener {
		@Override
		public void event(NetworkConfigEvent event) {
			if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
					&& event.configClass().equals(NameConfig.class)) {
				NameConfig config = cfgService.getConfig(appId, NameConfig.class);
				if (config != null) {
					segmentId = config.getSegmentId();
					subnets = config.getSubnets();
					hosts = config.getHosts();
					//log.info("segment ID: {}", segmentId);
					//log.info("subnets: {}", subnets);
					
					//for (DeviceId dpid : segmentId.keySet()) {
					//	log.info("dpid:{}, vid:{}", dpid.toString(), segmentId.get(dpid).toString());
					//}
					flowRuleInitialize();
				}
			}
		}
		private void flowRuleInitialize() {
			for (IpPrefix srcIp : subnets.keySet()) {
				DeviceId srcDevice = subnets.get(srcIp);
				VlanId srcVid = segmentId.get(srcDevice);
				for (IpPrefix dstIp : subnets.keySet()) {
					if (srcIp.equals(dstIp)) {
						continue;
					}
					DeviceId dstDevice = subnets.get(dstIp);
					VlanId dstVid = segmentId.get(dstDevice);

					//log.info("src: {}, {}, {}, dst: {}, {}, {}", srcIp.toString(), srcDevice.toString()
					//	, srcVid.toString(), dstIp.toString(), dstDevice.toString(), dstVid.toString());

					// find path between edge switch and install flow rule
					Set<Path> path  = pathService.getPaths(srcDevice, dstDevice);
					for (Path p : path) {
						List<Link> link = p.links();
						for (Link l : link) {
							if (l.src().deviceId().equals(srcDevice)) {
								// match dst IP & push vlan tag, output
								//log.info("match: {}, push:{}, output: {}", srcIp.toString(), dstVid.toString(), l.src().port().toString());
								TrafficSelector selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchIPDst(dstIp).build();
								TrafficTreatment treatment = DefaultTrafficTreatment.builder().pushVlan().setVlanId(dstVid).setOutput(l.src().port()).build();
								ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
									.withSelector(selector)
									.withTreatment(treatment)
									.withPriority(30)
									.withFlag(ForwardingObjective.Flag.VERSATILE)
									.fromApp(appId)
									.add();
								flowObjectiveService.forward(l.src().deviceId(), forwardingObjective);
							}
							else {
								// match vlan & output
								//log.info("match: {}, output: {}", dstVid.toString(), l.src().port().toString());
								TrafficSelector selector = DefaultTrafficSelector.builder().matchVlanId(dstVid).build();
								TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(l.src().port()).build();
								ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
									.withSelector(selector)
									.withTreatment(treatment)
									.withPriority(30)
									.withFlag(ForwardingObjective.Flag.VERSATILE)
									.fromApp(appId)
									.add();
								flowObjectiveService.forward(l.src().deviceId(), forwardingObjective);
							}
							if (l.dst().deviceId().equals(dstDevice)) {
								// match vlan, dst mac & pop vlan tag, output
								for (Map.Entry<MacAddress, ConnectPoint> h : hosts.entrySet()) {
									if (h.getValue().deviceId().equals(dstDevice)) {
										MacAddress dstMac = h.getKey();
										log.info("match: vid {}, mac {}, pop, output {}", dstVid.toString(), dstMac.toString(), h.getValue().port());
										TrafficSelector selector = DefaultTrafficSelector.builder().matchVlanId(dstVid).matchEthDst(dstMac).build();
										TrafficTreatment treatment = DefaultTrafficTreatment.builder().popVlan().setOutput(h.getValue().port()).build();
										ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
											.withSelector(selector)
											.withTreatment(treatment)
											.withPriority(30)
											.withFlag(ForwardingObjective.Flag.VERSATILE)
											.fromApp(appId)
											.add();
										flowObjectiveService.forward(l.dst().deviceId(), forwardingObjective);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private class VlanbasedsrProcessor implements PacketProcessor {
	@Override
		public void process(PacketContext context) {
			if (context.isHandled()) {
				return;
			}

			Ethernet parsedPkt = context.inPacket().parsed();

			if (parsedPkt.getEtherType() != Ethernet.TYPE_IPV4) {
				return;
			}

			MacAddress src = parsedPkt.getSourceMAC();
			MacAddress dst = parsedPkt.getDestinationMAC();

			ConnectPoint srcCp = context.inPacket().receivedFrom();
			ConnectPoint dstCp = hosts.get(dst);

			log.info("packet-in from switch {}", srcCp.deviceId().toString());

			// intra-device forwarding
			if(srcCp.deviceId().equals(dstCp.deviceId())) {
				// packet out
				log.info("src:{}, dst:{}, output:{}", src.toString(), dst.toString(), dstCp.port().toString());
				context.treatmentBuilder().setOutput(dstCp.port());
				context.send();
			}
		}
	}
}
