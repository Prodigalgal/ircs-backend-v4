package com.prodigalgal.ircs.notification.mail;

record RenderedMail(String from, String to, String subject, String content, boolean html) {
}
