package com.prodigalgal.ircs.common.outbound;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public interface OutboundAddressResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
