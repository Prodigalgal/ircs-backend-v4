package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;

public interface OutboundTransport {

    OutboundHttpResponse send(OutboundHttpRequest request) throws IOException, InterruptedException;
}
