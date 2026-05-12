import { useState } from "react";
import { getApiUrl, getApiKey, saveSettings, isConfigured } from "../api/client";

export function Settings() {
  const [url, setUrl] = useState(getApiUrl);
  const [key, setKey] = useState(getApiKey);
  const [saved, setSaved] = useState(false);

  function handleSave(e: React.FormEvent) {
    e.preventDefault();
    saveSettings(url, key);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  return (
    <div className="max-w-lg space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Settings</h1>
        <p className="mt-1 text-sm text-gray-500">
          Stored in your browser's localStorage — never sent anywhere except
          directly to your FiGuard server.
        </p>
      </div>

      {!isConfigured() && (
        <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-3 text-sm text-yellow-800">
          Enter your FiGuard API URL and API key to get started.
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-4">
        <div>
          <label
            htmlFor="api-url"
            className="block text-sm font-medium text-gray-700 mb-1"
          >
            FiGuard API URL
          </label>
          <input
            id="api-url"
            type="url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="http://localhost:8080"
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 font-mono placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <p className="mt-1 text-xs text-gray-400">
            The base URL of your FiGuard server. Leave empty to use the Vite proxy
            (recommended for local dev — set to <code>http://localhost:8080</code> only if
            running without the proxy).
          </p>
        </div>

        <div>
          <label
            htmlFor="api-key"
            className="block text-sm font-medium text-gray-700 mb-1"
          >
            API Key
          </label>
          <input
            id="api-key"
            type="password"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder="ab_live_…"
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 font-mono placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <p className="mt-1 text-xs text-gray-400">
            Your FiGuard API key. Stored only in localStorage.
          </p>
        </div>

        <button
          type="submit"
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 transition-colors"
        >
          {saved ? "Saved ✓" : "Save settings"}
        </button>
      </form>

    </div>
  );
}
