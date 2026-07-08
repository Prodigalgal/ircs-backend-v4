package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;

public interface OutboundStreamingTransport {

    OutboundStreamingHttpResponse send(OutboundHttpRequest request) throws IOException, InterruptedException;
}
