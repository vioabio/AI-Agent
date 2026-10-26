import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App as AntApp, ConfigProvider } from "antd";
import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { theme } from "../app/theme";

export function renderApp(element: ReactElement, options: { initialEntries?: string[] } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <ConfigProvider theme={theme}>
      <AntApp>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={options.initialEntries}>{element}</MemoryRouter>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>,
  );
}
