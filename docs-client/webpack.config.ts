import path from 'path';

import FaviconsWebpackPlugin from 'favicons-webpack-plugin';
import HtmlWebpackPlugin from 'html-webpack-plugin';
import { LicenseWebpackPlugin } from 'license-webpack-plugin';
import { Configuration, DefinePlugin } from 'webpack';
import WebpackDevServer from 'webpack-dev-server';
declare module 'webpack' {
  interface Configuration {
    devServer?: WebpackDevServer.Configuration;
  }
}

import { docServiceDebug } from './src/lib/header-provider';

const armeriaPort = process.env.ARMERIA_PORT || '8080';

const isDev = !!process.env.WEBPACK_DEV;
const isWindows = process.platform === 'win32';

const config: Configuration = {
  mode: isDev ? 'development' : 'production',
  devtool: isDev ? 'eval-source-map' : undefined,
  entry: {
    main: ['react-hot-loader/patch', './src/index.tsx'],
  },
  output: {
    path: path.resolve(process.cwd(), './build/web'),
    // We don't mount to '/' for production build since we want the code to be relocatable.
    publicPath: isDev ? '/' : '',
  },
  module: {
    rules: [
      {
        test: /\.ts(x?)$/,
        exclude: /node_modules/,
        use: [
          {
            loader: 'babel-loader',
            options: {
              presets: [
                [
                  '@babel/env',
                  {
                    modules: false,
                    useBuiltIns: 'entry',
                    corejs: 3,
                    targets: {
                      browsers: ['>1%', 'not ie 11', 'not op_mini all'],
                    },
                  },
                ],
                '@babel/react',
              ],
              plugins: ['react-hot-loader/babel'],
            },
          },
          {
            loader: 'ts-loader',
            options: {
              compilerOptions: {
                noEmit: false,
              },
              transpileOnly: true,
              onlyCompileBundledFiles: true,
              reportFiles: ['src/**/*.{ts,tsx}'],
            },
          },
        ],
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
      },
      {
        test: /\.(eot|otf|ttf|woff|woff2)$/,
        use: 'file-loader',
      },
    ],
  },
  resolve: {
    modules: ['src', 'node_modules'],
    extensions: ['.js', '.jsx', '.ts', '.tsx'],
    mainFields: ['browser', 'module', 'jsnext:main', 'main'],
  },
  plugins: [],
  devServer: {
    historyApiFallback: true,
    hot: true,
    open: 'docs/',
    port: 3000,
    proxy: [
      {
        path: '/',
        context: (pathname, req) =>
          !!req.headers[docServiceDebug] ||
          pathname.endsWith('specification.json') ||
          pathname.endsWith('versions.json') ||
          pathname.endsWith('injected.js'),
        target: `http://127.0.0.1:${armeriaPort}`,
        changeOrigin: true,
      },
    ],
    client: {
      overlay: {
        warnings: false,
        errors: true,
      },
    },
  },
};

// Configure plugins.
const plugins = config.plugins as any[];
plugins.push(new HtmlWebpackPlugin({
  template: './src/index.html',
  hash: true,
}));
plugins.push(new FaviconsWebpackPlugin({
  logo: './src/images/logo.png',
  // We don't need the many different icon versions of webapp mode and use light mode
  // to keep JAR size down.
  mode: 'light',
  devMode: 'light',
}));
// Do not add LicenseWebpackPlugin on Windows, because otherwise it will fail with a known issue.
if (!isWindows) {
  plugins.push(new LicenseWebpackPlugin({
    stats: {
      warnings: true,
      errors: true,
    },
    outputFilename: '../../../licenses/web-licenses.txt',
  }) as any);
}
plugins.push(new DefinePlugin({
  'process.env.WEBPACK_DEV': JSON.stringify(process.env.WEBPACK_DEV),
}));

export default config;
