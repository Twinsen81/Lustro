// Markup that runs script or breaks out of an element or an attribute when it
// reaches innerHTML unescaped. The escaping tests feed it to every viewer as a
// captured body and as a search query.
'use strict';

const PAYLOADS = [
    '<img src=x onerror=alert(1)>',
    '<script>alert(1)</script>',
    '</span><script>alert(1)</script>',
    '</mark><img src=x onerror=1><mark>',
    '"><img src=x onerror=1>',
    "'><img src=x onerror=1>",
    '<svg/onload=alert(1)>',
    '&lt;script&gt;',
    '&amp;lt;script&amp;gt;',
    '<!--<script>-->',
];

module.exports = { PAYLOADS };
