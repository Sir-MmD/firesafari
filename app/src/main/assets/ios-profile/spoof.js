// Runs in the page's own JavaScript world, in every frame, before any page script.
//
// The user agent, navigator.platform and navigator.appVersion are deliberately NOT set here.
// They are engine prefs (see IosProfile.kt) so that HTTP headers, workers and service
// workers report iPhone too. This script covers what prefs cannot: properties Safari has
// that Firefox lacks, and Firefox-only properties Safari does not have.
(() => {
  "use strict";

  // --- Make replacements print like built-in functions ---------------------------------

  const nativeToString = Function.prototype.toString;
  const disguised = new WeakMap();

  // Use real built-ins as templates so the output matches this engine's exact formatting.
  const nativeGetter = Object.getOwnPropertyDescriptor(Navigator.prototype, "userAgent").get;
  const getterTemplate = nativeToString.call(nativeGetter);
  const functionTemplate = nativeToString.call(Function.prototype.call);

  const accessorSource = (kind, prop) =>
    getterTemplate.includes("get userAgent")
      ? getterTemplate.replace("get userAgent", `${kind} ${prop}`)
      : getterTemplate.replace("userAgent", prop);
  const functionSource = (name) => functionTemplate.replace("call", name);

  function disguise(fn, source) {
    disguised.set(fn, source);
    return fn;
  }

  const toString = new Proxy(nativeToString, {
    apply(target, thisArg, args) {
      const source = disguised.get(thisArg);
      return source !== undefined ? source : Reflect.apply(target, thisArg, args);
    },
  });
  disguise(toString, nativeToString.call(nativeToString));
  Object.defineProperty(Function.prototype, "toString", { value: toString });

  // --- Helpers ---------------------------------------------------------------------------

  function defineValue(target, prop, value) {
    if (!target) return;
    const existing = Object.getOwnPropertyDescriptor(target, prop);
    const get = Object.getOwnPropertyDescriptor({ get [prop]() { return value; } }, prop).get;
    Object.defineProperty(target, prop, {
      get: disguise(get, accessorSource("get", prop)),
      set: undefined,
      enumerable: existing ? existing.enumerable : true,
      configurable: true,
    });
  }

  // Event handler property that accepts a handler but never fires (e.g. ongesturestart).
  function defineInertHandler(target, prop) {
    if (prop in target) return;
    let handler = null;
    const accessors = {
      get [prop]() { return handler; },
      set [prop](value) { handler = typeof value === "function" ? value : null; },
    };
    const { get, set } = Object.getOwnPropertyDescriptor(accessors, prop);
    Object.defineProperty(target, prop, {
      get: disguise(get, accessorSource("get", prop)),
      set: disguise(set, accessorSource("set", prop)),
      enumerable: true,
      configurable: true,
    });
  }

  // Delete a property wherever it lives on the prototype chain, so `prop in obj` is false.
  function remove(target, prop) {
    for (let o = target; o && o !== Object.prototype; o = Object.getPrototypeOf(o)) {
      if (Object.prototype.hasOwnProperty.call(o, prop)) {
        try { delete o[prop]; } catch (e) { /* non-configurable: leave it */ }
      }
    }
  }

  // --- navigator ---------------------------------------------------------------------------

  const nav = Navigator.prototype;
  defineValue(nav, "vendor", "Apple Computer, Inc.");
  defineValue(nav, "productSub", "20030107");
  defineValue(nav, "maxTouchPoints", 5);
  // iOS-only. True when the site was launched from the home screen, which is what this app is.
  defineValue(nav, "standalone", true);

  // Firefox (or Android) only; Safari on iPhone has none of these.
  for (const prop of ["oscpu", "buildID", "vibrate", "doNotTrack", "globalPrivacyControl",
                      "registerProtocolHandler", "taintEnabled", "mozGetUserMedia"]) {
    remove(nav, prop);
  }

  // --- window ------------------------------------------------------------------------------

  for (const prop of ["mozInnerScreenX", "mozInnerScreenY", "mozPaintCount", "InstallTrigger", "sidebar"]) {
    remove(window, prop);
  }

  // iOS Safari has these gesture event handlers; sites use `'ongesturestart' in window` as an iOS check.
  for (const prop of ["ongesturestart", "ongesturechange", "ongestureend"]) {
    defineInertHandler(window, prop);
  }

  // iOS 13+ requires asking before reading motion sensors; the static method only exists there.
  for (const name of ["DeviceMotionEvent", "DeviceOrientationEvent"]) {
    const ctor = window[name];
    if (typeof ctor !== "function" || "requestPermission" in ctor) continue;
    const requestPermission = { requestPermission() { return Promise.resolve("granted"); } }.requestPermission;
    Object.defineProperty(ctor, "requestPermission", {
      value: disguise(requestPermission, functionSource("requestPermission")),
      writable: true,
      enumerable: true,
      configurable: true,
    });
  }

  // --- CSS ---------------------------------------------------------------------------------

  // `CSS.supports('-webkit-touch-callout', 'none')` is a common iOS check; -moz- support is a Firefox tell.
  if (window.CSS && typeof CSS.supports === "function") {
    const nativeSupports = CSS.supports;
    const iosOnly = /-webkit-touch-callout/i;
    const geckoOnly = /(^|[\s(])-moz-/i;
    const supports = {
      supports(conditionOrProperty) {
        const text = String(conditionOrProperty);
        if (iosOnly.test(text)) return true;
        if (geckoOnly.test(text)) return false;
        return Reflect.apply(nativeSupports, CSS, arguments);
      },
    }.supports;
    Object.defineProperty(CSS, "supports", { value: disguise(supports, nativeToString.call(nativeSupports)) });
  }

  // Firefox-only style properties (style.MozAppearance and friends), plus Safari's touch-callout.
  for (const name of ["CSS2Properties", "CSSStyleProperties", "CSSStyleDeclaration"]) {
    const proto = window[name] && window[name].prototype;
    if (!proto) continue;
    for (const prop of Object.getOwnPropertyNames(proto)) {
      if (/^(Moz[A-Z]|-moz-)/.test(prop)) remove(proto, prop);
    }
  }
  const styleProto = (window.CSS2Properties || window.CSSStyleProperties || window.CSSStyleDeclaration).prototype;
  for (const prop of ["webkitTouchCallout", "WebkitTouchCallout"]) {
    if (prop in styleProto) continue;
    const accessors = { get [prop]() { return ""; }, set [prop](value) {} };
    const { get, set } = Object.getOwnPropertyDescriptor(accessors, prop);
    Object.defineProperty(styleProto, prop, {
      get: disguise(get, accessorSource("get", prop)),
      set: disguise(set, accessorSource("set", prop)),
      enumerable: true,
      configurable: true,
    });
  }
})();
