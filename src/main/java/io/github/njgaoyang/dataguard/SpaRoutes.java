package io.github.njgaoyang.dataguard;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serve the SPA shell for safe, fixed browser navigation paths only. */
@Controller
class SpaRoutes {
 @GetMapping({"/dashboard","/sources","/agents","/rules","/approvals","/records","/settings","/records/{id}"})
 String app(){return "forward:/index.html";}
}
